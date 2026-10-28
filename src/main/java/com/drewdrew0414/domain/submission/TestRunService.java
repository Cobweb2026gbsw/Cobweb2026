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

/** 사용자 입력 테스트와 서버 선정 비공개 2케이스 테스트를 서로 다른 저장/응답 경계로 처리합니다. */
@Service @RequiredArgsConstructor
public class TestRunService {
    private final JdbcTemplate db;
    private final ProblemService problems;
    private final ObjectMapper json;
    public record Case(String input,String expected) {}
    public record Request(String language,String sourceCode,List<Case> cases,boolean privateSample) {
        /** 기존 공개 예제/사용자 입력 요청의 생성자와 JSON 기본 동작을 보존합니다. */
        public Request(String language,String sourceCode,List<Case> cases) {this(language,sourceCode,cases,false);}
    }
    public record Result(String input,String expected,String actual,String status,int timeMs,Object diagnostic) {}
    private record PrivateCase(String input,String expected,String compareType) {}
    private static final Set<String> RESULT_STATUSES=Set.of("ACCEPTED","WRONG_ANSWER","TIME_LIMIT_EXCEEDED",
        "MEMORY_LIMIT_EXCEEDED","RUNTIME_ERROR","COMPILATION_ERROR","SYSTEM_ERROR");
    private static final Set<String> COMPARE_TYPES=Set.of("EXACT","TRIM_TRAILING_WHITESPACE","FLOAT_TOLERANCE","SPECIAL_CHECKER");

    @Transactional public long create(int number,Request request,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        if(request==null || !SubmissionService.validSource(request.sourceCode()))throw bad();
        // 비공개 모드에서는 클라이언트가 입력·예상값·케이스 선택을 끼워 넣을 수 없습니다.
        if(request.privateSample()?request.cases()!=null:request.cases()==null || request.cases().isEmpty() || request.cases().size()>3)
            throw bad();
        Language language=Language.parse(request.language());
        var accounts=db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",uid);
        if(accounts.isEmpty() || !"ACTIVE".equals(accounts.getFirst().get("status")))
            throw new ApiException(HttpStatus.FORBIDDEN,"현재 계정은 테스트할 수 없습니다.");
        db.queryForObject("SELECT id FROM user_test_run_queue_lock WHERE id=1 FOR UPDATE",Integer.class);
        if(request.privateSample() && db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR SHARE",number).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND);
        var problem=problems.load(number,user);
        // 정식 응시 시간창/다시보기 정책을 일반 테스트 API로 우회하지 않습니다.
        if(db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,problem.get("id"))>0)
            throw new ApiException(HttpStatus.CONFLICT,"시험 문제는 일반 테스트 실행을 지원하지 않습니다.");
        String mode=(String)problem.get("execution_mode");language.requireMode(mode);
        if("FUNCTION".equals(mode) && language!=Language.PYTHON && !ProblemService.functionAbiCompatible(problem))throw bad();
        if(db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=? AND language=?",Long.class,problem.get("id"),language.name())!=1)throw bad();
        // 이미 제한에 걸린 비공개 요청은 전역 큐 잠금 안에서 최대 32MiB의 실제 케이스를
        // 읽거나 JSON 검증하지 않습니다. 권한/언어 확인 뒤, 비공개 데이터 접근보다 먼저 거절합니다.
        if(request.privateSample())checkCapacity(uid);
        var signature=FunctionSignature.fromJson(json,problem.get("function_signature"));
        List<PrivateCase> privateCases=List.of();
        String inputCases;
        if(request.privateSample()) {
            privateCases=privateSnapshot(problem.get("id"),signature);
            // 기존 수동 큐의 512KiB JSON CHECK를 늘리지 않습니다. 실제 비공개 값은 별도
            // 서버 전용 테이블에만 스냅샷으로 두고 여기에는 값 없는 두 자리만 저장합니다.
            inputCases="[{\"input\":\"\",\"expected\":\"\"},{\"input\":\"\",\"expected\":\"\"}]";
        } else {
            for(var example:request.cases()) {
                if(example==null || !validText(example.input()) || !validText(example.expected()))throw bad();
                if(signature!=null)signature.validateCase(json,example.input(),example.expected());
            }
            inputCases=json.writeValueAsString(request.cases());
            // JSONB가 직렬화 구분자에 붙이는 공백도 DB 상한에 포함되므로 여유를 둡니다.
            if(inputCases.getBytes(StandardCharsets.UTF_8).length>524288-128)throw bad();
        }
        // 수동 테스트는 종전처럼 사용자 입력 검증 뒤에 용량을 검사해 400/429 순서를 유지합니다.
        if(!request.privateSample())checkCapacity(uid);
        long id=db.queryForObject("""
            INSERT INTO user_test_runs(user_id,problem_id,language,source_code,execution_mode,function_signature,time_limit_ms,memory_limit_mb,input_cases,private_sample)
            VALUES(?,?,?,?,?,CAST(? AS jsonb),?,?,CAST(? AS jsonb),?) RETURNING id
            """,Long.class,uid,problem.get("id"),language.name(),request.sourceCode(),mode,
            signature==null?null:signature.toJson(json),problem.get("time_limit_ms"),problem.get("memory_limit_mb"),inputCases,request.privateSample());
        for(int index=0;index<privateCases.size();index++) {
            var sample=privateCases.get(index);
            db.update("INSERT INTO user_test_run_private_cases(run_id,display_order,input_data,expected_output,compare_type) VALUES(?,?,?,?,?)",
                id,index+1,sample.input(),sample.expected(),sample.compareType());
        }
        return id;
    }
    public Map<String,Object> detail(long id,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        var rows=db.queryForList("""
            SELECT t.id,t.user_id,t.problem_id,p.problem_number,t.language,t.status,t.result_json::text AS result_json,t.private_sample,
                   jsonb_array_length(t.input_cases) AS test_count
            FROM user_test_runs t JOIN problems p ON p.id=t.problem_id WHERE t.id=? AND t.user_id=?
            """,id,uid);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND);
        var row=rows.getFirst();
        // 소유권뿐 아니라 현재 문제 접근 권한도 매번 다시 검사합니다.
        problems.load(((Number)row.get("problem_number")).intValue(),user);
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id"));result.put("problemNumber",row.get("problem_number"));
        result.put("language",row.get("language"));result.put("status",row.get("status"));
        boolean privateSample=isPrivateSample(row);
        result.put("privateSample",privateSample);result.put("testCount",privateSample?2:row.get("test_count"));
        // 저장된 JSON이 구버전 형식이거나 오염되어도 비공개 결과를 그대로 복사하지 않습니다.
        // 입력·정답·실제 출력·진단·테스트 ID는 null 필드조차 외부 응답에 만들지 않습니다.
        result.put("results",privateSample
            ?"COMPLETED".equals(row.get("status"))?privateResults(row.get("result_json")):List.of()
            :row.get("result_json")==null?List.of():json.readValue(row.get("result_json").toString(),List.class));
        return result;
    }
    @Transactional public Map<String,Object> claim() {
        expire();
        var rows=db.queryForList("""
            SELECT id,user_id,problem_id,language,source_code,execution_mode,function_signature::text AS function_signature,
                   time_limit_ms,memory_limit_mb,input_cases::text AS input_cases,status,result_json::text AS result_json,
                   judge_token,lease_until,created_at,completed_at,private_sample
            FROM user_test_runs WHERE status='QUEUED' ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """);
        if(rows.isEmpty())return null;
        var row=rows.getFirst();String token=UUID.randomUUID().toString();
        db.update("UPDATE user_test_runs SET status='RUNNING',judge_token=?,lease_until=((clock_timestamp() AT TIME ZONE 'UTC') + INTERVAL '4 minutes') WHERE id=?",token,row.get("id"));
        row.put("judge_token",token);return row;
    }
    public boolean current(Map<String,Object> run) {
        return db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE id=? AND judge_token=? AND status='RUNNING' AND lease_until>(clock_timestamp() AT TIME ZONE 'UTC')",Long.class,run.get("id"),run.get("judge_token"))==1;
    }
    public void finish(Map<String,Object> run,List<Result> results,boolean failed) {
        if(Thread.currentThread().isInterrupted())return;
        // 메모리의 run 플래그보다 DB에 저장된 모드가 권한의 기준입니다. 오래된/잘못된
        // 워커가 원문을 넣더라도 비공개 결과는 status/timeMs 두 필드만 DB에 남깁니다.
        var stored=db.queryForList("SELECT private_sample FROM user_test_runs WHERE id=? AND status='RUNNING' AND judge_token=? AND lease_until>(clock_timestamp() AT TIME ZONE 'UTC')",run.get("id"),run.get("judge_token"));
        if(stored.isEmpty())return;
        boolean privateSample=isPrivateSample(stored.getFirst());
        Object payload=results;
        if(privateSample) {
            // 두 케이스가 모두 끝나야 완료입니다. 부분 결과를 완료로 오인하거나 실패한
            // 실행의 일부만 공개하지 않으며, null 원소도 안전한 시스템 오류로 닫습니다.
            if(failed || results==null || results.size()!=2 || results.stream().anyMatch(Objects::isNull)) {failed=true;payload=List.of();}
            else payload=results.stream().map(result->privateResult(result.status(),result.timeMs())).toList();
        }
        db.update("""
            UPDATE user_test_runs SET status=?,result_json=CAST(? AS jsonb),completed_at=(clock_timestamp() AT TIME ZONE 'UTC'),judge_token=NULL,lease_until=NULL
            WHERE id=? AND status='RUNNING' AND judge_token=? AND lease_until>(clock_timestamp() AT TIME ZONE 'UTC')
            """,failed?"SYSTEM_ERROR":"COMPLETED",json.writeValueAsString(payload),run.get("id"),run.get("judge_token"));
    }
    public void expire() {
        // 중단된 테스트는 자동 재실행하지 않습니다. 이전 워커의 늦은 완료도 토큰으로 거절합니다.
        db.update("""
            UPDATE user_test_runs SET status='SYSTEM_ERROR',judge_token=NULL,lease_until=NULL,completed_at=(clock_timestamp() AT TIME ZONE 'UTC')
            WHERE (status='RUNNING' AND lease_until<=(clock_timestamp() AT TIME ZONE 'UTC'))
               OR (status='QUEUED' AND created_at<((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '15 minutes'))
            """);
    }
    private void checkCapacity(long uid) {
        expire();
        if(db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,uid)>=2 ||
           db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')",Long.class)>=60 ||
           db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND created_at>((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 minutes')",Long.class,uid)>=10)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"진행 중 테스트는 2개, 분당 요청은 10개까지입니다. 잠시 후 다시 시도해주세요.");
    }
    public List<Case> cases(Map<String,Object> run) {
        return List.of(json.readValue(run.get("input_cases").toString(),Case[].class));
    }
    public static boolean isPrivateSample(Map<String,Object> run) {
        Object value=run.get("private_sample");
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()!=0;
    }
    /** 워커 전용입니다. 접수 때 고정한 두 스냅샷만 읽고 현재 문제의 케이스를 재선정하지 않습니다. */
    public List<Case> privateCases(Map<String,Object> run) {
        if(!isPrivateSample(run))throw new IllegalStateException("Private sample mode is required");
        var rows=db.queryForList("SELECT display_order,input_data,expected_output FROM user_test_run_private_cases WHERE run_id=? ORDER BY display_order",run.get("id"));
        if(rows.size()!=2 || ((Number)rows.getFirst().get("display_order")).intValue()!=1 ||
           ((Number)rows.getLast().get("display_order")).intValue()!=2)
            throw new IllegalStateException("Private sample snapshot is incomplete");
        return rows.stream().map(row->new Case((String)row.get("input_data"),(String)row.get("expected_output"))).toList();
    }
    private List<PrivateCase> privateSnapshot(Object problemId,FunctionSignature signature) {
        // 문제 행의 공유 잠금 뒤 활성 케이스 두 개를 고정하므로 이후 편집도 접수 값을 바꾸지 않습니다.
        var selected=db.queryForList("SELECT input_data,expected_output,compare_type FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE ORDER BY display_order,id LIMIT 2 FOR SHARE",problemId);
        if(selected.size()!=2)throw new ApiException(HttpStatus.CONFLICT,"실행 가능한 채점 테스트가 2개 이상 준비되어야 합니다.");
        long total=0;
        var snapshot=new ArrayList<PrivateCase>();
        for(var row:selected) {
            String input=(String)row.get("input_data"),expected=(String)row.get("expected_output"),compare=(String)row.get("compare_type");
            if(!validPrivateText(input)||!validPrivateText(expected)||!COMPARE_TYPES.contains(compare))throw unavailablePrivateCases();
            total+=(long)input.getBytes(StandardCharsets.UTF_8).length+expected.getBytes(StandardCharsets.UTF_8).length;
            if(total>32L*1024*1024)throw unavailablePrivateCases();
            if(signature!=null)signature.validateCase(json,input,expected);
            snapshot.add(new PrivateCase(input,expected,compare));
        }
        return List.copyOf(snapshot);
    }
    private List<Map<String,Object>> privateResults(Object stored) {
        if(stored==null)throw new IllegalStateException("Private sample result is incomplete");
        var value=json.readTree(stored.toString());
        if(!value.isArray() || value.size()!=2)throw new IllegalStateException("Private sample result is incomplete");
        var result=new ArrayList<Map<String,Object>>();
        for(var row:value) {
            String status=row.has("status")&&row.get("status").isString()?row.get("status").stringValue():"SYSTEM_ERROR";
            int elapsed=row.has("timeMs")&&row.get("timeMs").isIntegralNumber()&&row.get("timeMs").canConvertToInt()?row.get("timeMs").intValue():0;
            result.add(privateResult(status,elapsed));
        }
        return List.copyOf(result);
    }
    private static Map<String,Object> privateResult(String status,int elapsed) {
        return Map.of("status",RESULT_STATUSES.contains(status==null?"":status)?status:"SYSTEM_ERROR","timeMs",Math.max(0,elapsed));
    }
    private static boolean validPrivateText(String text) {
        return text!=null&&StandardCharsets.UTF_8.newEncoder().canEncode(text)&&text.getBytes(StandardCharsets.UTF_8).length<=FunctionSignature.MAX_JSON_BYTES;
    }
    private static ApiException unavailablePrivateCases() {return new ApiException(HttpStatus.CONFLICT,"채점 테스트의 크기 또는 형식을 확인해주세요.");}
    private static boolean validText(String text) {
        return text!=null && StandardCharsets.UTF_8.newEncoder().canEncode(text) && text.getBytes(StandardCharsets.UTF_8).length<=65536;
    }
    private static ApiException bad() {return new ApiException(HttpStatus.BAD_REQUEST,"테스트는 최대 3개이며 코드·각 입력·예상값은 UTF-8 64 KiB 이내의 올바른 값이어야 합니다.");}
}
