package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** 공개 프로필은 허용된 열만 반환합니다. 이메일·비밀번호·로그인 로그는 공개하지 않습니다. */
@RestController @RequestMapping("/api") @RequiredArgsConstructor
public class ProfileController {
    private final JdbcTemplate db;
    public record Edit(@NotNull @Size(max=300) String bio,
                       @NotNull @Size(max=255) @Pattern(regexp="|https://github\\.com/[A-Za-z0-9_-]+/?") String githubUrl) {}
    @GetMapping("/ranking") public Object ranking(@RequestParam(defaultValue="0") int page) {
        return ApiResponse.success(db.queryForList("""
            SELECT username,bio,solved_count,submit_count,rank_type,rank_int FROM users
            WHERE status='ACTIVE' ORDER BY solved_count DESC,submit_count ASC,id LIMIT 30 OFFSET ?
            """,Math.min(Math.max(page,0),10000)*30));
    }
    @GetMapping("/profiles/{username}") public Object profile(@PathVariable String username) {
        var rows=db.queryForList("SELECT id,username,bio,github_url,solved_count,submit_count,rank_type,rank_int FROM users WHERE username=?",username);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var p=rows.getFirst(); Object uid=p.remove("id");
        p.put("solvedProblems",db.queryForList("""
            SELECT p.problem_number,p.problem_title FROM user_solved_problems s JOIN problems p ON p.id=s.problem_id
            WHERE s.user_id=? AND p.status='PUBLISHED' AND p.visibility='PUBLIC' ORDER BY s.solved_at DESC LIMIT 100
            """,uid));
        return ApiResponse.success(p);
    }
    @GetMapping("/me") public Object me(@AuthenticationPrincipal CustomUserDetails user) {
        ProblemService.requireUser(user); return profile(user.getUsername());
    }
    @PatchMapping("/me") public Object edit(@Valid @RequestBody Edit request,@AuthenticationPrincipal CustomUserDetails user) {
        long id=ProblemService.requireUser(user);
        db.update("UPDATE users SET bio=?,github_url=? WHERE id=?",request.bio(),request.githubUrl(),id);
        return ApiResponse.success(null);
    }
}
