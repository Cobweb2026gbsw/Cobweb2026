package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContestScorecardService {
    private final JdbcTemplate db;
    private final GroupService groups;
    private final ContestService contests;

    public record Cell(long userId, String username, int problemNumber, String problemTitle, int attempts,
                       String verdict, String firstAcceptedAt) {}

    // 제출한 참가자만 성적표에 들어갑니다. 미시도 문제도 출제 순서대로 한 셀씩 반환합니다.
    @Transactional(readOnly=true)
    public List<Cell> page(long gid,long cid,Long targetUser,long afterUser,CustomUserDetails user) {
        groups.requirePermission(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
        contests.contest(gid,cid,false);
        return db.query("""
            WITH participants AS (
                SELECT DISTINCT user_id FROM submissions
                WHERE contest_id=? AND user_id>? AND (CAST(? AS bigint) IS NULL OR user_id=?)
                ORDER BY user_id LIMIT 50
            ), attempts AS (
                SELECT s.user_id,s.problem_id,count(*) AS n,
                    min(s.submitted_at) FILTER (WHERE s.status='ACCEPTED') AS first_ac,
                    bool_or(s.status IN ('QUEUED','RUNNING')) AS pending,
                    bool_or(s.status <> 'SYSTEM_ERROR') AS has_judgement
                FROM submissions s JOIN participants u ON u.user_id=s.user_id WHERE s.contest_id=?
                GROUP BY s.user_id,s.problem_id)
            SELECT u.id,u.username,p.problem_number,p.problem_title,coalesce(a.n,0) AS attempts,a.first_ac,
                CASE WHEN a.n IS NULL THEN 'UNATTEMPTED' WHEN a.first_ac IS NOT NULL THEN 'ACCEPTED'
                     WHEN a.pending THEN 'PENDING' WHEN NOT a.has_judgement THEN 'SYSTEM_ERROR' ELSE 'UNSOLVED' END AS verdict
            FROM participants part JOIN users u ON u.id=part.user_id
            CROSS JOIN contest_problems cp JOIN problems p ON p.id=cp.problem_id
            LEFT JOIN attempts a ON a.user_id=u.id AND a.problem_id=p.id
            WHERE cp.contest_id=? ORDER BY u.id,cp.display_order
            """,(rs,i) -> new Cell(rs.getLong("id"),rs.getString("username"),rs.getInt("problem_number"),rs.getString("problem_title"),
                    rs.getInt("attempts"),rs.getString("verdict"),rs.getTimestamp("first_ac")==null ? null : ContestService.iso(rs.getTimestamp("first_ac"))),
                cid,afterUser,targetUser,targetUser,cid,cid);
    }
}
