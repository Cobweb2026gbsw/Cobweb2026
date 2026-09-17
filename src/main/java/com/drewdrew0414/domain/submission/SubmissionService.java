package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/** 제출 접수와 DB 큐를 관리합니다. 사용자 코드는 HTTP 요청 스레드에서 실행하지 않습니다. */
@Service @RequiredArgsConstructor
public class SubmissionService {
    private final JdbcTemplate db;
    private final ProblemService problems;

    @Transactional
    public long submit(int number,String code,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=problems.load(number,user);
        if (!"PUBLISHED".equals(p.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"공개된 문제만 제출할 수 있습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE",Long.class,p.get("id"))==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"채점 데이터가 준비되지 않았습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM submissions WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,uid)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"진행 중인 제출은 최대 5개입니다.");
        db.update("INSERT INTO submissions(user_id,problem_id,language,source_code) VALUES(?,?,'PYTHON',?)",uid,p.get("id"),code);
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("UPDATE users SET submit_count=submit_count+1 WHERE id=?",uid);
        db.update("UPDATE problems SET submission_count=submission_count+1 WHERE id=?",p.get("id"));
        return id;
    }
    public List<Map<String,Object>> mine(CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        return db.queryForList("""
            SELECT s.id,p.problem_number,p.problem_title,s.status,s.score,s.execution_time_ms,s.submitted_at
            FROM submissions s JOIN problems p ON p.id=s.problem_id WHERE s.user_id=? ORDER BY s.id DESC LIMIT 50
            """,uid);
    }
    public Map<String,Object> detail(long id,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        var rows=db.queryForList("SELECT id,user_id,status,score,execution_time_ms,submitted_at,judged_at FROM submissions WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var result=rows.getFirst();
        if(((Number)result.get("user_id")).longValue()!=uid && !ProblemService.operator(user))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // 입력과 정답은 반환하지 않습니다. 사용자는 판정과 소요 시간만 확인합니다.
        result.put("cases",db.queryForList("SELECT status,execution_time_ms FROM submission_test_case_results WHERE submission_id=? ORDER BY id",id));
        return result;
    }
    @Transactional
    public Map<String,Object> claim() {
        // 중단된 워커의 작업은 시스템 오류로 마무리합니다. 오래된 워커는 토큰 검사로 결과를 덮어쓸 수 없습니다.
        db.update("UPDATE submissions SET status='SYSTEM_ERROR',judged_at=CURRENT_TIMESTAMP,judge_token=NULL WHERE status='RUNNING' AND claimed_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 20 MINUTE)");
        var rows=db.queryForList("SELECT * FROM submissions WHERE status='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty()) return null;
        var s=rows.getFirst(); String token=UUID.randomUUID().toString();
        db.update("UPDATE submissions SET status='RUNNING',claimed_at=CURRENT_TIMESTAMP,judge_token=? WHERE id=?",token,s.get("id"));
        s.put("judge_token",token);
        return s;
    }
    public Map<String,Object> problem(Object id) {
        return db.queryForMap("SELECT execution_mode,time_limit_ms,memory_limit_mb FROM problems WHERE id=?",id);
    }
    public List<Map<String,Object>> cases(Object pid) {
        return db.queryForList("SELECT id,input_data,expected_output FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE ORDER BY display_order",pid);
    }
    @Transactional
    public void finish(Map<String,Object> s,List<JudgeResult> results) {
        // 사용자 → 문제 → 제출 순서로 잠가 제출 접수와 결과 반영의 교착 위험을 줄입니다.
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",s.get("user_id"));
        db.queryForList("SELECT id FROM problems WHERE id=? FOR UPDATE",s.get("problem_id"));
        var current=db.queryForList("SELECT status,judge_token FROM submissions WHERE id=? FOR UPDATE",s.get("id"));
        if(current.isEmpty() || !"RUNNING".equals(current.getFirst().get("status")) ||
            !Objects.equals(s.get("judge_token"),current.getFirst().get("judge_token"))) return;
        String status=results.isEmpty()?"SYSTEM_ERROR":results.stream().map(JudgeResult::status)
            .filter(v->!"ACCEPTED".equals(v)).findFirst().orElse("ACCEPTED");
        int elapsed=results.stream().mapToInt(JudgeResult::elapsedMs).max().orElse(0);
        for(var r:results) db.update("INSERT INTO submission_test_case_results(submission_id,test_case_id,status,execution_time_ms) VALUES(?,?,?,?)",
            s.get("id"),r.caseId(),r.status(),r.elapsedMs());
        db.update("UPDATE submissions SET status=?,score=?,execution_time_ms=?,judged_at=CURRENT_TIMESTAMP,judge_token=NULL WHERE id=?",
            status,"ACCEPTED".equals(status)?100:0,elapsed,s.get("id"));
        if("ACCEPTED".equals(status)) {
            db.update("UPDATE problems SET accepted_count=accepted_count+1 WHERE id=?",s.get("problem_id"));
            int inserted=db.update("INSERT IGNORE INTO user_solved_problems(user_id,problem_id) VALUES(?,?)",s.get("user_id"),s.get("problem_id"));
            if(inserted==1) {
                db.update("UPDATE users SET solved_count=solved_count+1 WHERE id=?",s.get("user_id"));
                int count=db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,s.get("user_id"));
                // MVP 임시 정책: 10문제마다 세부 등급 상승, 50문제마다 큰 등급 상승합니다.
                String[] ranks={"INITIATE","SKILLED","ELITE","EXPERT","LEGEND","MYTHIC","ABSOLUTE"};
                db.update("UPDATE users SET rank_type=?,rank_int=? WHERE id=?",ranks[Math.min(count/50,6)],
                    count>=350?1:5-(count%50)/10,s.get("user_id"));
            }
        }
    }
    public record JudgeResult(Long caseId,String status,int elapsedMs) {}
}
