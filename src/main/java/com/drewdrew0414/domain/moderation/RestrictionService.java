package com.drewdrew0414.domain.moderation;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service @RequiredArgsConstructor
public class RestrictionService {
    public enum Feature { POST,COMMENT }
    public record Request(@NotBlank @Size(max=16) String username,@NotNull Feature feature,
                          @NotBlank @Size(max=500) String reason,@Future @NotNull OffsetDateTime expiresAt) {}
    private final JdbcTemplate db;

    @Transactional
    public long restrict(Request input,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        // 글 작성, 제재 등록이 모두 대상 사용자 행에서 직렬화됩니다.
        var matches=db.queryForList("SELECT id,role FROM users WHERE lower(username)=lower(?) FOR UPDATE",input.username().strip());
        if (matches.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"회원을 찾을 수 없습니다.");
        var target=matches.getFirst();
        if (!"USER".equals(target.get("role"))) throw new ApiException(HttpStatus.CONFLICT,"일반 회원에게만 작성 제한을 적용할 수 있습니다.");
        var active=db.queryForList("""
            SELECT id FROM community_restrictions WHERE user_id=? AND feature=? AND revoked_at IS NULL AND expires_at>clock_timestamp()
            """,target.get("id"),input.feature().name());
        if (!active.isEmpty()) throw new ApiException(HttpStatus.CONFLICT,"이미 적용 중인 제한이 있습니다.");
        var inserted=db.queryForList("""
            INSERT INTO community_restrictions(user_id,feature,reason,expires_at,issued_by)
            SELECT ?,?,?,?,? WHERE CAST(? AS timestamptz)>clock_timestamp() RETURNING id
            """,Long.class,target.get("id"),input.feature().name(),input.reason().strip(),input.expiresAt(),user.getUserId(),input.expiresAt());
        if (inserted.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"미래의 종료 시각을 입력해주세요.");
        return inserted.getFirst();
    }

    @Transactional
    public void revoke(long id,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        var targets=db.queryForList("SELECT user_id FROM community_restrictions WHERE id=?",Long.class,id);
        if (targets.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",targets.getFirst());
        if(db.update("""
            UPDATE community_restrictions SET revoked_by=?,revoked_at=clock_timestamp()
            WHERE id=? AND revoked_at IS NULL AND expires_at>clock_timestamp()
            """,user.getUserId(),id)==0) throw new ApiException(HttpStatus.CONFLICT,"이미 종료된 제한입니다.");
    }

    public List<Map<String,Object>> list(String username,int page,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if (page<0) throw new ApiException(HttpStatus.BAD_REQUEST);
        return db.queryForList("""
            SELECT r.id,u.username,r.feature,r.reason,issuer.username AS issuer,revoker.username AS revoker,
              to_jsonb(r.expires_at) #>> '{}' AS "expiresAt",
              CASE WHEN r.revoked_at IS NOT NULL THEN 'REVOKED' WHEN r.expires_at<=clock_timestamp() THEN 'EXPIRED' ELSE 'ACTIVE' END AS status
            FROM community_restrictions r JOIN users u ON u.id=r.user_id JOIN users issuer ON issuer.id=r.issued_by
            LEFT JOIN users revoker ON revoker.id=r.revoked_by WHERE (?='' OR lower(u.username)=lower(?))
            ORDER BY r.id DESC LIMIT 30 OFFSET ?
            """,username,username,(long)page*30);
    }
}
