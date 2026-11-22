package com.drewdrew0414.domain.submission;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service @RequiredArgsConstructor
class AdminJudgeQueue {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    @Transactional public Map<String,Object> claim() {
        var rows=db.queryForList("""
            SELECT j.id,j.batch_id,j.language,j.attempts,COALESCE(j.reference_code,s.source_code) AS code,
                b.kind,b.config::text AS config FROM admin_judge_jobs j JOIN admin_judge_batches b ON b.id=j.batch_id
                LEFT JOIN submissions s ON s.id=j.submission_id
            WHERE b.state='RUNNING' AND (j.state='QUEUED' OR (j.state='RUNNING' AND j.lease_until<now()))
            ORDER BY j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
            """);
        if(rows.isEmpty()) return null;
        var job=rows.getFirst();
        UUID token=UUID.randomUUID();job.put("token",token);
        db.update("UPDATE admin_judge_jobs SET state='RUNNING',attempts=attempts+1,token=?,lease_until=now()+interval '5 minutes' WHERE id=?",token,job.get("id"));
        return job;
    }
    boolean renew(Map<String,Object> job) {
        return db.update("""
            UPDATE admin_judge_jobs j SET lease_until=now()+interval '5 minutes' FROM admin_judge_batches b
            WHERE j.batch_id=b.id AND b.state='RUNNING' AND j.id=? AND j.token=? AND j.state='RUNNING'
            """,job.get("id"),job.get("token"))==1;
    }
    @Transactional public void finish(Map<String,Object> job,List<AdminJudgeWorker.CaseResult> cases,String image,JudgeDiagnostic diagnostic) {
        long batch=AdminJudgingService.number(job.get("batch_id"));
        String state=db.queryForObject("SELECT state FROM admin_judge_batches WHERE id=? FOR UPDATE",String.class,batch);
        if(!"RUNNING".equals(state)) return;
        String verdict=cases.isEmpty() || cases.stream().anyMatch(r->"SYSTEM_ERROR".equals(r.status()))?"SYSTEM_ERROR":
            cases.stream().map(AdminJudgeWorker.CaseResult::status).filter(s->!"ACCEPTED".equals(s)).findFirst().orElse("ACCEPTED");
        int elapsed=cases.stream().mapToInt(AdminJudgeWorker.CaseResult::timeMs).max().orElse(0);
        String safe=diagnostic!=null && diagnostic.matches(verdict)?diagnostic.toJson():null;
        if(db.update("""
            UPDATE admin_judge_jobs SET state='DONE',verdict=?,elapsed_ms=?,results=?::jsonb,diagnostic=?::jsonb,image_id=?,token=NULL,lease_until=NULL
            WHERE id=? AND token=? AND state='RUNNING'
            """,verdict,elapsed,json.writeValueAsString(cases),safe,image,job.get("id"),job.get("token"))!=1) return;
        db.update("""
            UPDATE admin_judge_batches SET state=CASE WHEN EXISTS (
                SELECT 1 FROM admin_judge_jobs WHERE batch_id=? AND verdict='SYSTEM_ERROR') OR
                (SELECT COUNT(DISTINCT image_id) FROM admin_judge_jobs WHERE batch_id=?)>1 THEN 'FAILED' ELSE 'READY' END
            WHERE id=? AND NOT EXISTS (SELECT 1 FROM admin_judge_jobs WHERE batch_id=? AND state<>'DONE')
            """,batch,batch,batch,batch);
    }
    List<Map<String,Object>> inputs(Object batch) {
        return db.queryForList("""
            SELECT kind,case_id,display_order,input_data,expected_output FROM admin_judge_inputs
            WHERE batch_id=? ORDER BY kind,display_order
            """,batch);
    }
}
