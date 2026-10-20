package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** 제출 접수와 DB 큐를 관리합니다. 사용자 코드는 HTTP 요청 스레드에서 실행하지 않습니다. */
@Service @RequiredArgsConstructor
public class SubmissionService {
    private final JdbcTemplate db;
    private final ProblemService problems;
    private final ContestService contests;

    @Transactional
    public long submit(int number,String code,CustomUserDetails user) {
        return submit(number,code,Language.PYTHON,user);
    }
    @Transactional
    public long submit(int number,String code,Language language,CustomUserDetails user) {
        return accept(null,number,code,language,user);
    }
    /** 시험 답안은 같은 격리 채점 큐를 이용하되 일반 풀이 통계와 분리합니다. */
    @Transactional
    public long submitContest(long contestId,int number,String code,Language language,CustomUserDetails user) {
        return accept(contestId,number,code,language,user);
    }
    private long accept(Long contestId,int number,String code,Language language,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        if(code==null || code.isBlank() || code.getBytes(StandardCharsets.UTF_8).length>65536 || language==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"소스 코드는 UTF-8 64KiB 이내여야 합니다.");
        // 시험 설정 변경도 시험→문제 순서로 잠급니다. 접수도 먼저 시험을 잠가 교착을 피합니다.
        if(contestId!=null && db.queryForList("SELECT id FROM group_contests WHERE id=? FOR UPDATE",contestId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"시험을 찾을 수 없습니다.");
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        var rows=db.queryForList("SELECT * FROM problems WHERE problem_number=? FOR UPDATE",number);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"문제를 찾을 수 없습니다.");
        Map<String,Object> p;
        if(contestId==null) {
            p=problems.load(number,user);
            // 시험에 쓰이는 문제는 일반 제출 경로로 우회해 풀거나 통계를 올릴 수 없습니다.
            if(db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,p.get("id"))>0)
                throw new ResponseStatusException(HttpStatus.CONFLICT,"시험 문제는 해당 시험에서 제출해 주세요.");
        } else {
            p=rows.getFirst();
            contests.requireSubmission(contestId,((Number)p.get("id")).longValue(),user);
        }
        language.requireMode((String)p.get("execution_mode"));
        if ("FUNCTION".equals(p.get("execution_mode")) && language!=Language.PYTHON &&
            !ProblemService.functionAbiCompatible(p))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기존 함수형 테스트는 Python 제출만 지원합니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=? AND language=?",Integer.class,
                p.get("id"),language.name())==0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"문제에서 허용하지 않은 언어입니다.");
        if (contestId==null && !"PUBLISHED".equals(p.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"공개된 문제만 제출할 수 있습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE",Long.class,p.get("id"))==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"채점 데이터가 준비되지 않았습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM submissions WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,uid)>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"진행 중인 제출은 최대 5개입니다.");
        if(contestId==null) db.update("""
            INSERT INTO submissions(user_id,problem_id,language,source_code,contest_id)
            VALUES(?,?,?,?,NULL)
            """,uid,p.get("id"),language.name(),code);
        else {
            // 권한 확인 후 검사가 길어져 종료 시각을 넘긴 요청도 저장 시점에 거절합니다.
            int inserted=db.update("""
                INSERT INTO submissions(user_id,problem_id,language,source_code,contest_id,submitted_at)
                SELECT ?,?,?,?,id,UTC_TIMESTAMP(6) FROM group_contests
                WHERE id=? AND starts_at<=UTC_TIMESTAMP(6) AND ends_at>UTC_TIMESTAMP(6)
                """,uid,p.get("id"),language.name(),code,contestId);
            if(inserted!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"시험 응시 시간이 아닙니다.");
        }
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        if(contestId==null) {
            db.update("UPDATE users SET submit_count=submit_count+1 WHERE id=?",uid);
            db.update("UPDATE problems SET submission_count=submission_count+1 WHERE id=?",p.get("id"));
        }
        return id;
    }
    public List<Map<String,Object>> mine(CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        return db.queryForList("""
            SELECT s.id,p.problem_number,p.problem_title,s.language,s.status,s.score,s.execution_time_ms,s.submitted_at
            FROM submissions s JOIN problems p ON p.id=s.problem_id
            WHERE s.user_id=? AND s.contest_id IS NULL ORDER BY s.id DESC LIMIT 50
            """,uid);
    }
    public Map<String,Object> detail(long id,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        var rows=db.queryForList("SELECT id,user_id,contest_id,language,status,score,execution_time_ms,submitted_at,judged_at FROM submissions WHERE id=?",id);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var result=rows.getFirst();
        if(result.get("contest_id")!=null) {
            contests.requireSubmissionRead(((Number)result.get("contest_id")).longValue(),
                ((Number)result.get("user_id")).longValue(),user);
            result.put("sourceCode",db.queryForObject("SELECT source_code FROM submissions WHERE id=?",String.class,id));
        } else if(((Number)result.get("user_id")).longValue()!=uid && !ProblemService.operator(user))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        // 입력과 정답은 반환하지 않습니다. 사용자는 판정과 소요 시간만 확인합니다.
        result.put("cases",db.queryForList("SELECT status,execution_time_ms FROM submission_test_case_results WHERE submission_id=? ORDER BY id",id));
        return result;
    }
    @Transactional
    public Map<String,Object> claim() {
        recoverExpired();
        var rows=db.queryForList("SELECT * FROM submissions WHERE status='QUEUED' AND judge_attempts<3 ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty()) return null;
        var s=rows.getFirst(); String token=UUID.randomUUID().toString();
        db.update("""
            UPDATE submissions SET status='RUNNING',claimed_at=CURRENT_TIMESTAMP(6),
                judge_heartbeat_at=CURRENT_TIMESTAMP(6),judge_token=?,judge_attempts=judge_attempts+1
            WHERE id=?
            """,token,s.get("id"));
        s.put("judge_token",token);
        s.put("judge_attempts",((Number)s.get("judge_attempts")).intValue()+1);
        return s;
    }
    /** 끝난 lease만 회수합니다. 세 번째 실행도 중단되면 사용자에게 시스템 오류를 남깁니다. */
    @Transactional
    public void recoverExpired() {
        String expired="""
            status='RUNNING' AND (
                judge_heartbeat_at < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 20 MINUTE)
                OR (judge_heartbeat_at IS NULL AND
                    COALESCE(claimed_at,submitted_at) < DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 20 MINUTE))
            )
            """;
        db.update("UPDATE submissions SET status='QUEUED',claimed_at=NULL,judge_heartbeat_at=NULL,judge_token=NULL WHERE "
            +expired+" AND judge_attempts<3");
        db.update("UPDATE submissions SET status='SYSTEM_ERROR',judged_at=IF(contest_id IS NULL,CURRENT_TIMESTAMP(6),UTC_TIMESTAMP(6)),judge_heartbeat_at=NULL,judge_token=NULL WHERE "
            +expired+" AND judge_attempts>=3");
    }
    /** 반환값 0은 다른 워커가 lease를 회수했거나 제출이 이미 끝났음을 뜻합니다. */
    public boolean heartbeat(Map<String,Object> submission) {
        // 변경 행 수를 반환하는 JDBC 드라이버에서도 같은 마이크로초의 갱신을 성공으로 판정합니다.
        return db.update("""
            UPDATE submissions SET judge_heartbeat_at=
                GREATEST(COALESCE(judge_heartbeat_at,CURRENT_TIMESTAMP(6)),CURRENT_TIMESTAMP(6)) + INTERVAL 1 MICROSECOND
            WHERE id=? AND status='RUNNING' AND judge_token=?
            """,submission.get("id"),submission.get("judge_token"))==1;
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
        var current=db.queryForList("SELECT status,judge_token,contest_id FROM submissions WHERE id=? FOR UPDATE",s.get("id"));
        if(current.isEmpty() || !"RUNNING".equals(current.getFirst().get("status")) ||
            !Objects.equals(s.get("judge_token"),current.getFirst().get("judge_token"))) return;
        String status=results.isEmpty()?"SYSTEM_ERROR":results.stream().map(JudgeResult::status)
            .filter(v->!"ACCEPTED".equals(v)).findFirst().orElse("ACCEPTED");
        int elapsed=results.stream().mapToInt(JudgeResult::elapsedMs).max().orElse(0);
        for(var r:results) db.update("INSERT INTO submission_test_case_results(submission_id,test_case_id,status,execution_time_ms) VALUES(?,?,?,?)",
            s.get("id"),r.caseId(),r.status(),r.elapsedMs());
        db.update("UPDATE submissions SET status=?,score=?,execution_time_ms=?,judged_at=IF(contest_id IS NULL,CURRENT_TIMESTAMP(6),UTC_TIMESTAMP(6)),judge_heartbeat_at=NULL,judge_token=NULL WHERE id=?",
            status,"ACCEPTED".equals(status)?100:0,elapsed,s.get("id"));
        // DB의 현재 contest_id를 사용합니다. 워커가 넘긴 맵으로 일반 풀이 통계를 오염시키지 않습니다.
        if("ACCEPTED".equals(status) && current.getFirst().get("contest_id")==null) {
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
