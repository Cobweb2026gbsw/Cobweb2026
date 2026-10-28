package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;

/** 제출 접수와 DB 큐를 관리합니다. 사용자 코드는 HTTP 요청 스레드에서 실행하지 않습니다. */
@Service @RequiredArgsConstructor
public class SubmissionService {
    public static final int MAX_SOURCE_BYTES=65536;
    private static final Set<String> REUSABLE_STATUSES=Set.of("ACCEPTED","WRONG_ANSWER","TIME_LIMIT_EXCEEDED",
        "MEMORY_LIMIT_EXCEEDED","RUNTIME_ERROR","COMPILATION_ERROR");
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
        if(!validSource(code) || language==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"소스 코드는 UTF-8 64KiB 이내여야 합니다.");
        // 시험 설정 변경도 시험→문제 순서로 잠급니다. 접수도 먼저 시험을 잠가 교착을 피합니다.
        if(contestId!=null && db.queryForList("SELECT id FROM group_contests WHERE id=? FOR UPDATE",contestId).isEmpty())
            throw new ApiException(HttpStatus.NOT_FOUND,"시험을 찾을 수 없습니다.");
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        var rows=db.queryForList("SELECT * FROM problems WHERE problem_number=? FOR UPDATE",number);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"문제를 찾을 수 없습니다.");
        Map<String,Object> p;
        if(contestId==null) {
            p=problems.load(number,user);
            // 시험에 쓰이는 문제는 일반 제출 경로로 우회해 풀거나 통계를 올릴 수 없습니다.
            if(db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,p.get("id"))>0)
                throw new ApiException(HttpStatus.CONFLICT,"시험 문제는 해당 시험에서 제출해 주세요.");
        } else {
            p=rows.getFirst();
            contests.requireSubmission(contestId,((Number)p.get("id")).longValue(),user);
        }
        language.requireMode((String)p.get("execution_mode"));
        if ("FUNCTION".equals(p.get("execution_mode")) && language!=Language.PYTHON &&
            !ProblemService.functionAbiCompatible(p))
            throw new ApiException(HttpStatus.BAD_REQUEST,"기존 함수형 테스트는 Python 제출만 지원합니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=? AND language=?",Integer.class,
                p.get("id"),language.name())==0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"문제에서 허용하지 않은 언어입니다.");
        if (contestId==null && !"PUBLISHED".equals(p.get("status")))
            throw new ApiException(HttpStatus.CONFLICT,"공개된 문제만 제출할 수 있습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE",Long.class,p.get("id"))==0)
            throw new ApiException(HttpStatus.CONFLICT,"채점 데이터가 준비되지 않았습니다.");
        if (db.queryForObject("SELECT COUNT(*) FROM submissions WHERE user_id=? AND status IN ('QUEUED','RUNNING')",Long.class,uid)>=5)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"진행 중인 제출은 최대 5개입니다.");
        long id;
        if(contestId==null) id=db.queryForObject("""
            INSERT INTO submissions(user_id,problem_id,language,source_code,contest_id)
            VALUES(?,?,?,?,NULL) RETURNING id
            """,Long.class,uid,p.get("id"),language.name(),code);
        else {
            // 권한 확인 후 검사가 길어져 종료 시각을 넘긴 요청도 저장 시점에 거절합니다.
            var inserted=db.queryForList("""
                INSERT INTO submissions(user_id,problem_id,language,source_code,contest_id,submitted_at)
                SELECT ?,?,?,?,id,(clock_timestamp() AT TIME ZONE 'UTC') FROM group_contests
                WHERE id=? AND starts_at<=(clock_timestamp() AT TIME ZONE 'UTC') AND ends_at>(clock_timestamp() AT TIME ZONE 'UTC')
                RETURNING id
                """,Long.class,uid,p.get("id"),language.name(),code,contestId);
            if(inserted.size()!=1) throw new ApiException(HttpStatus.FORBIDDEN,"시험 응시 시간이 아닙니다.");
            id=inserted.getFirst();
        }
        db.update("UPDATE submissions SET source_sha256=? WHERE id=?",sourceDigest(code),id);
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
        var rows=db.queryForList("SELECT id,user_id,contest_id,language,status,score,execution_time_ms,submitted_at,judged_at,reused_from_submission_id,diagnostic_json::text AS diagnostic_json FROM submissions WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        var result=rows.getFirst();
        if(result.get("contest_id")!=null) {
            contests.requireSubmissionRead(((Number)result.get("contest_id")).longValue(),
                ((Number)result.get("user_id")).longValue(),user);
            result.put("sourceCode",db.queryForObject("SELECT source_code FROM submissions WHERE id=?",String.class,id));
        } else if(((Number)result.get("user_id")).longValue()!=uid && !ProblemService.operator(user))
                throw new ApiException(HttpStatus.NOT_FOUND);
        result.put("reusedFromSubmissionId",result.remove("reused_from_submission_id"));
        // 권한 검사 뒤에만 안전한 고정 설명을 반환하며 DB의 원문/임의 문자열은 복사하지 않습니다.
        var diagnostic=JudgeDiagnostic.fromJson(result.remove("diagnostic_json"),(String)result.get("status"));
        result.put("diagnostic",diagnostic==null?null:diagnostic.publicValue());
        // 입력·정답·stderr 원문은 반환하지 않습니다.
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
            UPDATE submissions SET status='RUNNING',claimed_at=(clock_timestamp() AT TIME ZONE 'UTC'),
                judge_heartbeat_at=(clock_timestamp() AT TIME ZONE 'UTC'),judge_token=?,judge_attempts=judge_attempts+1
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
                judge_heartbeat_at < ((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '20 minutes')
                OR (judge_heartbeat_at IS NULL AND
                    COALESCE(claimed_at,submitted_at) < ((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '20 minutes'))
            )
            """;
        db.update("UPDATE submissions SET status='QUEUED',claimed_at=NULL,judge_heartbeat_at=NULL,judge_token=NULL WHERE "
            +expired+" AND judge_attempts<3");
        db.update("UPDATE submissions SET status='SYSTEM_ERROR',judged_at=(clock_timestamp() AT TIME ZONE 'UTC'),judge_heartbeat_at=NULL,judge_token=NULL WHERE "
            +expired+" AND judge_attempts>=3");
    }
    /** 반환값 0은 다른 워커가 lease를 회수했거나 제출이 이미 끝났음을 뜻합니다. */
    public boolean heartbeat(Map<String,Object> submission) {
        // 변경 행 수를 반환하는 JDBC 드라이버에서도 같은 마이크로초의 갱신을 성공으로 판정합니다.
        return db.update("""
            UPDATE submissions SET judge_heartbeat_at=
                GREATEST(COALESCE(judge_heartbeat_at,(clock_timestamp() AT TIME ZONE 'UTC')),(clock_timestamp() AT TIME ZONE 'UTC')) + INTERVAL '1 microsecond'
            WHERE id=? AND status='RUNNING' AND judge_token=?
            """,submission.get("id"),submission.get("judge_token"))==1;
    }
    public Map<String,Object> problem(Object id) {
        return db.queryForMap("SELECT execution_mode,time_limit_ms,memory_limit_mb,function_abi_compatible,function_signature::text AS function_signature FROM problems WHERE id=?",id);
    }
    public List<Map<String,Object>> cases(Object pid) {
        return db.queryForList("SELECT id,input_data,expected_output,compare_type,display_order FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE ORDER BY display_order,id",pid);
    }
    /** UTF-8 바이트 상한이며 공백/개행/유니코드를 정규화하거나 잘라서 저장하지 않습니다. */
    static boolean validSource(String code) {
        return code!=null && !code.isBlank() && StandardCharsets.UTF_8.newEncoder().canEncode(code) &&
            code.getBytes(StandardCharsets.UTF_8).length<=MAX_SOURCE_BYTES;
    }
    static String sourceDigest(String code) {
        return digest(code.getBytes(StandardCharsets.UTF_8));
    }
    private static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); }
    }
    /** 길이를 붙인 UTF-8 필드로 모호한 연결을 피하고 테스트 원문·순서·ABI·제한도 포함합니다. */
    static String contextDigest(Map<String,Object> problem,List<Map<String,Object>> tests,String imageId,String engine) {
        if(imageId==null || !imageId.matches("sha256:[0-9a-f]{64}") || engine==null || !engine.matches("[0-9a-f]{64}"))
            return null;
        MessageDigest hash;
        try {hash=MessageDigest.getInstance("SHA-256");}
        catch(java.security.NoSuchAlgorithmException error) {throw new IllegalStateException("SHA-256 unavailable",error);}
        try {
            // 대용량 케이스 묶음을 통째로 두 번 복사하지 않고 기존 바이트 형식 그대로 해시에 흘려보냅니다.
            var data=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),hash));
            for(Object value:List.of("COBWEB_JUDGEMENT_V1",imageId,engine,
                    problem.get("execution_mode"),problem.get("time_limit_ms"),problem.get("memory_limit_mb"),
                    ProblemService.functionAbiCompatible(problem))) writeField(data,value);
            writeField(data,problem.get("function_signature"));
            data.writeInt(tests.size());
            for(var test:tests) for(String key:List.of("id","input_data","expected_output","compare_type","display_order"))
                writeField(data,test.get(key));
            data.flush();
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.io.IOException | IllegalArgumentException | NullPointerException ignored) {
            // 선택적 캐시의 불완전 메타데이터만 miss로 처리합니다. 해시 엔진/프로그래밍 장애는
            // 숨기지 않고 워커의 SYSTEM_ERROR 경계로 전파합니다.
            return null;
        }
    }
    private static void writeField(DataOutputStream output,Object value) throws java.io.IOException {
        if(value==null) { output.writeInt(-1);return; }
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);output.write(bytes);
    }
    /**
     * 새 제출/lease/통계 기록은 유지하고 본인의 완전히 같은 판정만 복제합니다.
     * 이미지/판정기/현재 테스트를 증명할 수 없거나 원본 접근 권한이 없으면 캐시 miss입니다.
     */
    @Transactional
    public boolean tryReuse(Map<String,Object> claim,String imageId,String engine) {
        if(imageId==null || engine==null) return false;
        // 시험→사용자→문제→제출 순서를 접수와 일치시킵니다.
        if(claim.get("contest_id")!=null && db.queryForList(
                "SELECT id FROM group_contests WHERE id=? FOR UPDATE",claim.get("contest_id")).isEmpty()) return false;
        var users=db.queryForList("SELECT id,status FROM users WHERE id=? FOR UPDATE",claim.get("user_id"));
        if(users.isEmpty() || !"ACTIVE".equals(users.getFirst().get("status"))) return false;
        db.queryForList("SELECT id FROM problems WHERE id=? FOR UPDATE",claim.get("problem_id"));
        var rows=db.queryForList("SELECT * FROM submissions WHERE id=? FOR UPDATE",claim.get("id"));
        if(rows.isEmpty()) return false;
        var current=rows.getFirst();
        if(!"RUNNING".equals(current.get("status")) || !Objects.equals(claim.get("judge_token"),current.get("judge_token")) ||
            !sameId(claim.get("user_id"),current.get("user_id")) || !sameId(claim.get("problem_id"),current.get("problem_id")) ||
            !sameId(claim.get("contest_id"),current.get("contest_id"))) return false;
        if(!cacheReadAllowed(current)) return false;
        var tests=cases(current.get("problem_id"));
        if(tests.isEmpty()) return false;
        String context=contextDigest(problem(current.get("problem_id")),tests,imageId,engine);
        if(context==null) return false;
        String code=(String)current.get("source_code");
        var candidates=db.queryForList("""
            SELECT id,source_code,status,score,execution_time_ms,diagnostic_json::text AS diagnostic_json FROM submissions
            WHERE user_id=? AND problem_id=? AND language=? AND contest_id IS NOT DISTINCT FROM ?
              AND id<? AND source_sha256=? AND judge_context_sha256=?
              AND status IN ('ACCEPTED','WRONG_ANSWER','TIME_LIMIT_EXCEEDED','MEMORY_LIMIT_EXCEEDED','RUNTIME_ERROR','COMPILATION_ERROR')
              AND convert_to(source_code,'UTF8')=convert_to(CAST(? AS text),'UTF8')
            ORDER BY id DESC LIMIT 20
            """,current.get("user_id"),current.get("problem_id"),current.get("language"),current.get("contest_id"),
            current.get("id"),sourceDigest(code),context,code);
        for(var original:candidates) {
            String originalCode=(String)original.get("source_code");
            // 해시/DB collation과 별도로 문자 원문과 실제 UTF-8 바이트를 모두 최종 비교합니다.
            if(!code.equals(originalCode) || !Arrays.equals(code.getBytes(StandardCharsets.UTF_8),originalCode.getBytes(StandardCharsets.UTF_8))) continue;
            var stored=db.queryForList("SELECT test_case_id,status,execution_time_ms FROM submission_test_case_results WHERE submission_id=? ORDER BY id",original.get("id"));
            var results=reusableResults(original,stored,tests);
            if(results==null) continue;
            // DB 트랜잭션이 롤백돼도 worker의 원래 claim 맵에는 재사용 표시가 남지 않습니다.
            var reusedClaim=new HashMap<>(claim);
            reusedClaim.put("judge_image_id",imageId);reusedClaim.put("judge_engine",engine);reusedClaim.put("judge_context",context);
            reusedClaim.put("reused_from_submission_id",original.get("id"));
            finish(reusedClaim,results);
            return true;
        }
        return false;
    }
    private static boolean sameId(Object first,Object second) {
        return first==null?second==null:second instanceof Number number && first instanceof Number value && value.longValue()==number.longValue();
    }
    private boolean cacheReadAllowed(Map<String,Object> submission) {
        // 일반 detail은 본인 기록을 볼 수 있습니다. 후보 SQL도 반드시 동일 user_id만 검색합니다.
        if(submission.get("contest_id")==null) return true;
        // 종료/멤버십 상실/권한 변경 시 재사용하지 않습니다. 그 뒤의 원래 격리 채점은 계속합니다.
        return db.queryForObject("""
            SELECT COUNT(*) FROM group_contests c
            JOIN group_members gm ON gm.group_id=c.group_id AND gm.user_id=?
            JOIN group_roles gr ON gr.group_id=gm.group_id AND gr.id=gm.role_id
            JOIN contest_problems cp ON cp.contest_id=c.id AND cp.problem_id=?
            WHERE c.id=? AND gr.can_take_contests=TRUE
              AND c.starts_at<=(clock_timestamp() AT TIME ZONE 'UTC') AND c.ends_at>(clock_timestamp() AT TIME ZONE 'UTC')
            """,Long.class,submission.get("user_id"),submission.get("problem_id"),submission.get("contest_id"))>0;
    }
    static List<JudgeResult> reusableResults(Map<String,Object> original,List<Map<String,Object>> rows,List<Map<String,Object>> tests) {
        String status=(String)original.get("status");
        if(!REUSABLE_STATUSES.contains(status) || rows.isEmpty()) return null;
        boolean compilation="COMPILATION_ERROR".equals(status);
        if(compilation ? rows.size()!=1 || rows.getFirst().get("test_case_id")!=null : rows.size()!=tests.size()) return null;
        var results=new ArrayList<JudgeResult>();
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);String result=(String)row.get("status");
            if(!REUSABLE_STATUSES.contains(result) || !(row.get("execution_time_ms") instanceof Number elapsed) || elapsed.intValue()<0 ||
                (compilation?!"COMPILATION_ERROR".equals(result):!sameId(row.get("test_case_id"),tests.get(i).get("id")))) return null;
            results.add(new JudgeResult(row.get("test_case_id")==null?null:((Number)row.get("test_case_id")).longValue(),result,elapsed.intValue()));
        }
        String combined=results.stream().map(JudgeResult::status).filter(value->!"ACCEPTED".equals(value)).findFirst().orElse("ACCEPTED");
        if(!status.equals(combined) || !(original.get("score") instanceof Number score) || score.intValue()!=("ACCEPTED".equals(status)?100:0) ||
            !(original.get("execution_time_ms") instanceof Number elapsed) || elapsed.intValue()!=results.stream().mapToInt(JudgeResult::elapsedMs).max().orElse(0)) return null;
        var diagnostic=JudgeDiagnostic.fromJson(original.get("diagnostic_json"),status);
        if(diagnostic!=null) for(int index=0;index<results.size();index++) if(status.equals(results.get(index).status())) {
            var result=results.get(index);
            results.set(index,new JudgeResult(result.caseId(),result.status(),result.elapsedMs(),diagnostic));
            break;
        }
        return List.copyOf(results);
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
        db.update("UPDATE submissions SET status=?,score=?,execution_time_ms=?,judged_at=(clock_timestamp() AT TIME ZONE 'UTC'),judge_heartbeat_at=NULL,judge_token=NULL WHERE id=?",
            status,"ACCEPTED".equals(status)?100:0,elapsed,s.get("id"));
        // 최종 판정과 같은 종류의 첫 진단만 보관합니다. 다른 케이스의 성공/시스템 출력은 남기지 않습니다.
        var diagnostic=results.stream().filter(r->status.equals(r.status())).map(JudgeResult::diagnostic)
            .filter(Objects::nonNull).filter(value->value.matches(status)).findFirst().orElse(null);
        db.update("UPDATE submissions SET diagnostic_json=CAST(? AS jsonb) WHERE id=?",diagnostic==null?null:diagnostic.toJson(),s.get("id"));
        String context=null;
        if(REUSABLE_STATUSES.contains(status) && s.get("judge_context") instanceof String expected) {
            String actual=contextDigest(problem(s.get("problem_id")),cases(s.get("problem_id")),
                (String)s.get("judge_image_id"),(String)s.get("judge_engine"));
            if(expected.equals(actual)) context=actual;
        }
        db.update("UPDATE submissions SET judge_context_sha256=?,reused_from_submission_id=? WHERE id=?",
            context,context==null?null:s.get("reused_from_submission_id"),s.get("id"));
        // DB의 현재 contest_id를 사용합니다. 워커가 넘긴 맵으로 일반 풀이 통계를 오염시키지 않습니다.
        if("ACCEPTED".equals(status) && current.getFirst().get("contest_id")==null) {
            db.update("UPDATE problems SET accepted_count=accepted_count+1 WHERE id=?",s.get("problem_id"));
            int inserted=db.update("INSERT INTO user_solved_problems(user_id,problem_id) VALUES(?,?) ON CONFLICT (user_id,problem_id) DO NOTHING",s.get("user_id"),s.get("problem_id"));
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
    public record JudgeResult(Long caseId,String status,int elapsedMs,JudgeDiagnostic diagnostic) {
        public JudgeResult(Long caseId,String status,int elapsedMs){this(caseId,status,elapsedMs,null);}
    }
}
