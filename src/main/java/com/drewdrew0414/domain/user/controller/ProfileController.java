package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.submission.Language;
import com.drewdrew0414.domain.user.exception.DuplicateUsernameException;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.Map;

/** 공개 프로필은 허용된 열만 반환합니다. 이메일·비밀번호·로그인 로그는 공개하지 않습니다. */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ProfileController {
    private final JdbcTemplate db;
    public record Edit(@Size(max=16, message="아이디는 16자 이하여야 합니다.") String username,
                       @NotNull @Size(max=300) String bio,
                       @NotNull @Size(max=255) @Pattern(regexp="|https://github\\.com/[A-Za-z0-9_-]+/?") String githubUrl) {}
    @GetMapping("/ranking") public Object ranking(@RequestParam(defaultValue="0") int page) {
        return ApiResponse.success(db.queryForList("""
            SELECT username,bio,solved_count,submit_count,rank_type,rank_int,rank_points FROM users
            WHERE status='ACTIVE' ORDER BY rank_points DESC,solved_count DESC,submit_count ASC,id LIMIT 30 OFFSET ?
            """,Math.min(Math.max(page,0),10000)*30));
    }
    /** 기존 이름의 '/', '..', 유니코드를 경로 정규화 없이 그대로 조회하는 공개 별칭입니다. */
    @GetMapping("/profiles") public Object profileQuery(@RequestParam String username) {
        return profile(username);
    }
    @GetMapping("/profiles/{username}") public Object profile(@PathVariable String username) {
        return ApiResponse.success(profileData(username));
    }
    private static final String PROFILE_COLUMNS = """
        SELECT id,username,bio,github_url,solved_count,submit_count,rank_type,rank_int,rank_points,
               (SELECT min(min_points) FROM cobweb_rank_thresholds WHERE min_points > users.rank_points) AS next_rank_points
        FROM users""";
    private java.util.Map<String,Object> profileData(String username) {
        return profileData(db.queryForList(PROFILE_COLUMNS+" WHERE lower(username)=lower(?)",username));
    }
    private java.util.Map<String,Object> profileData(java.util.List<java.util.Map<String,Object>> rows) {
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        var p=rows.getFirst(); Object uid=p.remove("id");
        p.put("solvedProblems",db.queryForList("""
            SELECT p.problem_number,p.problem_title FROM user_solved_problems s JOIN problems p ON p.id=s.problem_id
            WHERE s.user_id=? AND p.status='PUBLISHED' AND p.visibility='PUBLIC' AND p.group_id IS NULL
            ORDER BY s.solved_at DESC LIMIT 100
            """,uid));
        p.put("topLanguages", db.query("""
            SELECT s.language, COUNT(DISTINCT s.problem_id) AS solved_count
            FROM submissions s JOIN problems p ON p.id=s.problem_id
            WHERE s.user_id=? AND s.status='ACCEPTED' AND s.contest_id IS NULL
              AND p.status='PUBLISHED' AND p.visibility='PUBLIC' AND p.group_id IS NULL
            GROUP BY s.language
            ORDER BY solved_count DESC, s.language COLLATE "C" ASC
            LIMIT 3
            """, (rs, rowNum) -> {
                String language = rs.getString("language");
                return Map.of("language", language, "label", Language.displayName(language),
                        "solved_count", rs.getLong("solved_count"));
            }, uid));
        return p;
    }
    @GetMapping("/me") public Object me(@AuthenticationPrincipal CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        var profile=profileData(db.queryForList(PROFILE_COLUMNS+" WHERE id=?",uid));
        // 내 계정에서만 초안 분리에 필요한 불변 ID를 제공합니다. 공개 프로필에는 넣지 않습니다.
        // BIGINT를 JavaScript Number로 반올림하지 않도록 10진 문자열로 전달합니다.
        profile.put("id",Long.toString(uid));
        return ApiResponse.success(profile);
    }
    @PatchMapping("/me") public Object edit(@Valid @RequestBody Edit request,@AuthenticationPrincipal CustomUserDetails user) {
        long id=ProblemService.requireUser(user);
        // 기존 클라이언트는 username을 보내지 않습니다. PK를 기준으로 한 번에 저장해
        // 다른 계정이 같은 이름을 먼저 선택해도 소개만 따로 변경되지 않게 합니다.
        String name = request.username() == null ? null : request.username().strip();
        if (name != null && (name.isBlank() || name.codePoints().anyMatch(Character::isISOControl))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "유효한 아이디를 입력해주세요.");
        }
        try {
            int updated = db.update("""
                    UPDATE users SET access_token_version=access_token_version+
                        CASE WHEN username<>COALESCE(?,username) THEN 1 ELSE 0 END,
                        username=COALESCE(?,username),bio=?,github_url=? WHERE id=? AND status='ACTIVE'
                    """,name,name,request.bio(),request.githubUrl(),id);
            if (updated == 0) throw new ApiException(HttpStatus.UNAUTHORIZED);
        } catch (DuplicateKeyException conflict) {
            // 이 UPDATE가 바꾸는 고유 키는 lower(username) 하나입니다.
            throw new DuplicateUsernameException();
        }
        return ApiResponse.success(null);
    }
}
