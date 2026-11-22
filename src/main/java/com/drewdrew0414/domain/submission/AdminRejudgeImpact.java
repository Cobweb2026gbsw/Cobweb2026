package com.drewdrew0414.domain.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** 미리보기는 제출 상태를 쿼리 안에서만 대체합니다. 저장된 새 판정은 명시적인 확정에서만 반영합니다. */
@Component @RequiredArgsConstructor
class AdminRejudgeImpact {
    private final JdbcTemplate db;

    Map<String,Object> preview(long batch) {
        var result=db.queryForMap("""
            SELECT count(*) FILTER (WHERE old_result->>'status'<>'ACCEPTED' AND verdict='ACCEPTED') AS "newlyAccepted",
                   count(*) FILTER (WHERE old_result->>'status'='ACCEPTED' AND verdict<>'ACCEPTED') AS "lostAccepted",
                   count(*) FILTER (WHERE old_result->>'status'<>verdict) AS "changedVerdicts",
                   count(DISTINCT s.user_id) FILTER (WHERE old_result->>'status'<>'ACCEPTED' AND verdict='ACCEPTED') AS "gainingUsers"
            FROM admin_judge_jobs j JOIN submissions s ON s.id=j.submission_id WHERE j.batch_id=?
            """,batch);
        Long contest=db.queryForObject("SELECT contest_id FROM admin_judge_batches WHERE id=?",Long.class,batch);
        if(contest==null) {
            result.put("points",db.queryForList("""
                WITH batch AS (SELECT id,problem_id FROM admin_judge_batches WHERE id=?),
                users_changed AS (
                    SELECT DISTINCT s.user_id FROM batch b JOIN admin_judge_jobs j ON j.batch_id=b.id
                    JOIN submissions s ON s.id=j.submission_id WHERE (s.status='ACCEPTED')<>(j.verdict='ACCEPTED')
                ), affected AS (
                    SELECT u.id,u.username,u.rank_points,u.rank_type,u.rank_int,CASE
                      WHEN l.user_id IS NULL AND future.solved THEN p.reward_points
                      WHEN l.user_id IS NOT NULL AND NOT future.solved THEN -l.awarded_points ELSE 0 END AS delta
                    FROM users_changed a JOIN users u ON u.id=a.user_id CROSS JOIN batch b JOIN problems p ON p.id=b.problem_id
                    LEFT JOIN user_solved_problems l ON l.user_id=a.user_id AND l.problem_id=b.problem_id
                    CROSS JOIN LATERAL (SELECT EXISTS (SELECT 1 FROM submissions s LEFT JOIN admin_judge_jobs j
                      ON j.batch_id=b.id AND j.submission_id=s.id WHERE s.problem_id=b.problem_id AND s.user_id=u.id
                      AND s.contest_id IS NULL AND COALESCE(j.verdict,s.status)='ACCEPTED') AS solved) future
                )
                SELECT a.username,a.rank_points AS "beforePoints",a.rank_points+a.delta AS "afterPoints",
                    a.rank_type||' '||a.rank_int AS "beforeRank",t.rank_type||' '||t.rank_int AS "afterRank"
                FROM affected a CROSS JOIN LATERAL (SELECT rank_type,rank_int FROM cobweb_rank_thresholds
                    WHERE min_points<=a.rank_points+a.delta ORDER BY min_points DESC LIMIT 1) t
                WHERE delta<>0 ORDER BY a.id
                """,batch));
        } else {
            result.put("standings",db.queryForList("""
                WITH participants AS (
                    SELECT s.user_id,s.problem_id,s.submitted_at,s.status,COALESCE(j.verdict,s.status) AS future
                    FROM submissions s LEFT JOIN admin_judge_jobs j ON j.batch_id=? AND j.submission_id=s.id WHERE s.contest_id=?
                ), times AS (
                    SELECT user_id,problem_id,MIN(submitted_at) FILTER(WHERE status='ACCEPTED') AS old_ac,
                        MIN(submitted_at) FILTER(WHERE future='ACCEPTED') AS new_ac FROM participants GROUP BY user_id,problem_id
                ), totals AS (
                    SELECT user_id,count(old_ac) AS old_solved,count(new_ac) AS new_solved,
                        COALESCE(sum(greatest(0,floor(extract(epoch FROM(old_ac-c.starts_at))))) FILTER(WHERE old_ac IS NOT NULL),0) AS old_time,
                        COALESCE(sum(greatest(0,floor(extract(epoch FROM(new_ac-c.starts_at))))) FILTER(WHERE new_ac IS NOT NULL),0) AS new_time
                    FROM times CROSS JOIN group_contests c WHERE c.id=? GROUP BY user_id
                ), ranks AS (
                    SELECT *,row_number() OVER(ORDER BY old_solved DESC,old_time,user_id) AS old_rank,
                        row_number() OVER(ORDER BY new_solved DESC,new_time,user_id) AS new_rank FROM totals
                ) SELECT u.username,old_rank AS "beforeRank",new_rank AS "afterRank",old_solved AS "beforeSolved",new_solved AS "afterSolved",
                      old_time AS "beforeSeconds",new_time AS "afterSeconds"
                    FROM ranks r JOIN users u ON u.id=r.user_id WHERE old_rank<>new_rank OR old_solved<>new_solved OR old_time<>new_time
                    ORDER BY old_rank
                """,batch,contest,contest));
        }
        return result;
    }

    void apply(long batch,long problem,Map<String,Object> impact) {
        // 한 제출의 캐시를 비워도 같은 원문을 재사용했던 다른 제출이 다음 캐시 원본이 될 수 있습니다.
        // 같은 판정 조건과 UTF-8 원문의 캐시 자격만 없애고, 원래 제출의 점수·출처·케이스 결과는 보존합니다.
        db.update("""
            UPDATE submissions cached SET judge_context_sha256=NULL
            FROM admin_judge_jobs j JOIN submissions original ON original.id=j.submission_id
            WHERE j.batch_id=? AND cached.id<>original.id
              AND cached.user_id=original.user_id AND cached.problem_id=original.problem_id
              AND cached.language=original.language AND cached.contest_id IS NOT DISTINCT FROM original.contest_id
              AND cached.judge_context_sha256=(j.old_result->>'context')::char(64)
              AND cached.source_sha256=encode(sha256(convert_to(original.source_code,'UTF8')),'hex')::char(64)
              AND convert_to(cached.source_code,'UTF8')=convert_to(original.source_code,'UTF8')
            """,batch);
        // 이전 판정과 케이스는 배치 접수 때 old_result에 보존했습니다. 기존 제출 ID와 시각은 유지합니다.
        db.update("DELETE FROM submission_test_case_results r USING admin_judge_jobs j WHERE j.batch_id=? AND j.submission_id=r.submission_id",batch);
        db.update("""
            INSERT INTO submission_test_case_results(submission_id,test_case_id,status,execution_time_ms)
            SELECT j.submission_id,(r->>'caseId')::bigint,r->>'status',(r->>'timeMs')::int
            FROM admin_judge_jobs j CROSS JOIN LATERAL jsonb_array_elements(j.results) r WHERE j.batch_id=?
            ORDER BY j.id,(r->>'order')::integer
            """,batch);
        db.update("""
            UPDATE submissions s SET status=j.verdict, score=CASE WHEN j.verdict='ACCEPTED' THEN 100 ELSE 0 END,
                execution_time_ms=j.elapsed_ms,judged_at=clock_timestamp() AT TIME ZONE 'UTC',memory_usage_kb=NULL,
                diagnostic_json=j.diagnostic,judge_context_sha256=NULL,reused_from_submission_id=NULL
            FROM admin_judge_jobs j WHERE j.batch_id=? AND s.id=j.submission_id
            """,batch);
        if (!impact.containsKey("points")) return;
        db.update("""
            WITH affected AS (SELECT DISTINCT s.user_id FROM admin_judge_jobs j JOIN submissions s ON s.id=j.submission_id
                WHERE j.batch_id=? AND j.verdict='ACCEPTED' AND j.old_result->>'status'<>'ACCEPTED'),
            inserted AS (INSERT INTO user_solved_problems(user_id,problem_id,solved_at)
                SELECT a.user_id,s.problem_id,MIN(s.submitted_at) FROM affected a JOIN submissions s ON s.user_id=a.user_id
                WHERE s.problem_id=? AND s.contest_id IS NULL AND s.status='ACCEPTED' GROUP BY a.user_id,s.problem_id
                ON CONFLICT DO NOTHING RETURNING user_id)
            UPDATE users u SET solved_count=solved_count+1 FROM inserted WHERE u.id=inserted.user_id
            """,batch,problem);
        db.update("""
            WITH removed AS (DELETE FROM user_solved_problems l WHERE l.problem_id=?
                AND EXISTS (SELECT 1 FROM admin_judge_jobs j JOIN submissions s ON s.id=j.submission_id WHERE j.batch_id=?
                    AND s.user_id=l.user_id AND j.old_result->>'status'='ACCEPTED' AND j.verdict<>'ACCEPTED')
                AND NOT EXISTS (SELECT 1 FROM submissions s WHERE s.problem_id=l.problem_id AND s.user_id=l.user_id
                    AND s.contest_id IS NULL AND s.status='ACCEPTED') RETURNING user_id,awarded_points)
            UPDATE users u SET rank_points=rank_points-removed.awarded_points,solved_count=solved_count-1
                FROM removed WHERE u.id=removed.user_id
            """,problem,batch);
        int delta=((Number)impact.get("newlyAccepted")).intValue()-((Number)impact.get("lostAccepted")).intValue();
        if(delta!=0) db.update("UPDATE problems SET accepted_count=accepted_count+? WHERE id=?",delta,problem);
    }
}
