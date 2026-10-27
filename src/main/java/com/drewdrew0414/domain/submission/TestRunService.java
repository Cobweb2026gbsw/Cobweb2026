package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 오직 사용자가 보낸 입력·예상값만 보관합니다. 비공개 테스트 테이블은 읽지 않습니다. */
@Service @RequiredArgsConstructor
public class TestRunService {
    private final JdbcTemplate db;
    private final ProblemService problems;
    private final ObjectMapper json;
    public record Case(String input,String expected) {}
    public record Request(String language,String sourceCode,List<Case> cases) {}
    public record Result(String input,String expected,String actual,String status,int timeMs,Object diagnostic) {}

    @Transactional public long create(int number,Request request,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        if(request==null || !SubmissionService.validSource(request.sourceCode()) || request.cases()==null ||
           request.cases().isEmpty() || request.cases().size()>3)throw bad();
        Language language=Language.parse(request.language());
        var accounts=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",uid);
        if(accounts.isEmpty() || !"ACTIVE".equals(accounts.getFirst().get("status")))
            throw new ApiException(HttpStatus.FORBIDDEN,"현재 계정은 테스트할 수 없습니다.");
        db.queryForObject("SELECT id FROM user_test_run_queue_lock WHERE id=1 FOR UPDATE",Integer.class);
        var problem=problems.load(number,user);
        // 정식 응시 시간창/다시보기 정책을 일반 테스트 API로 우회하지 않습니다.
        if(db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,problem.get("id"))>0)
            throw new ApiException(HttpStatus.CONFLICT,"시험 문제는 일반 테스트 실행을 지원하지 않습니다.");
        String mode=(String)problem.get("execution_mode");language.requireMode(mode);
        if("FUNCTION".equals(mode) && language!=Language.PYTHON && !ProblemService.functionAbiCompatible(problem))throw bad();
        if(db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=? AND language=?",Long.class,problem.get("id"),language.name())!=1)throw bad();
        var signature=FunctionSignature.fromJson(json,problem.get("function_signature"));
        for(var example:request.cases()) {
            if(example==null || !validText(example.input()) || !validText(example.expected()))throw bad();
            if(signature!=null)signature.validateCase(json,example.input(),example.expected());
        }
        String inputCases=json.writeValueAsString(request.cases());
        // MySQL이 JSON 객체 구분자에 붙이는 공백도 DB 상한에 포함되므로 여유를 둡니다.
        if(inputCases.getBytes(StandardCharsets.UTF_8).length>524288-128)throw bad();
        expire();
        if(db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,uid)>=2 ||
           db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')",Long.class)>=60 ||
           db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND created_at>DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE)",Long.class,uid)>=10)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"진행 중 테스트는 2개, 분당 요청은 10개까지입니다. 잠시 후 다시 시도해주세요.");
        db.update("""
            INSERT INTO user_test_runs(user_id,problem_id,language,source_code,execution_mode,function_signature,time_limit_ms,memory_limit_mb,input_cases)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,uid,problem.get("id"),language.name(),request.sourceCode(),mode,
            signature==null?null:signature.toJson(json),problem.get("time_limit_ms"),problem.get("memory_limit_mb"),inputCases);
        return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    public Map<String,Object> detail(long id,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        var rows=db.queryForList("""
            SELECT t.id,t.user_id,t.problem_id,p.problem_number,t.language,t.status,t.result_json
            FROM user_test_runs t JOIN problems p ON p.id=t.problem_id WHERE t.id=? AND t.user_id=?
            """,id,uid);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND);
        var row=rows.getFirst();
        // 소유권뿐 아니라 현재 문제 접근 권한도 매번 다시 검사합니다.
        problems.load(((Number)row.get("problem_number")).intValue(),user);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id"));result.put("problemNumber",row.get("problem_number"));
        result.put("language",row.get("language"));result.put("status",row.get("status"));
        result.put("results",row.get("result_json")==null?List.of():json.readValue(row.get("result_json").toString(),List.class));
        return result;
    }
    @Transactional public Map<String,Object> claim() {
        expire();
        var rows=db.queryForList("SELECT * FROM user_test_runs WHERE status='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return null;
        var row=rows.getFirst();String token=UUID.randomUUID().toString();
        db.update("UPDATE user_test_runs SET status='RUNNING',judge_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 4 MINUTE) WHERE id=?",token,row.get("id"));
        row.put("judge_token",token);return row;
    }
    public boolean current(Map<String,Object> run) {
        return db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE id=? AND judge_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6)",Long.class,run.get("id"),run.get("judge_token"))==1;
    }
    public void finish(Map<String,Object> run,List<Result> results,boolean failed) {
        if(Thread.currentThread().isInterrupted())return;
        db.update("""
            UPDATE user_test_runs SET status=?,result_json=?,completed_at=CURRENT_TIMESTAMP(6),judge_token=NULL,lease_until=NULL
            WHERE id=? AND status='RUNNING' AND judge_token=? AND lease_until>CURRENT_TIMESTAMP(6)
            """,failed?"SYSTEM_ERROR":"COMPLETED",json.writeValueAsString(results),run.get("id"),run.get("judge_token"));
    }
    public void expire() {
        // 중단된 테스트는 자동 재실행하지 않습니다. 이전 워커의 늦은 완료도 토큰으로 거절합니다.
        db.update("""
            UPDATE user_test_runs SET status='SYSTEM_ERROR',judge_token=NULL,lease_until=NULL,completed_at=CURRENT_TIMESTAMP(6)
            WHERE (status='RUNNING' AND lease_until<=CURRENT_TIMESTAMP(6))
               OR (status='QUEUED' AND created_at<DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 15 MINUTE))
            """);
    }
    public List<Case> cases(Map<String,Object> run) {
        return List.of(json.readValue(run.get("input_cases").toString(),Case[].class));
    }
    private static boolean validText(String text) {
        return text!=null && StandardCharsets.UTF_8.newEncoder().canEncode(text) && text.getBytes(StandardCharsets.UTF_8).length<=65536;
    }
    private static ApiException bad() {return new ApiException(HttpStatus.BAD_REQUEST,"테스트는 최대 3개이며 코드·각 입력·예상값은 UTF-8 64 KiB 이내의 올바른 값이어야 합니다.");}
}
