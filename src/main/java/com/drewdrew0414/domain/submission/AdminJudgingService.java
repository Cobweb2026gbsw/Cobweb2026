package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.group.GroupPermission;
import com.drewdrew0414.domain.submission.AdminJudgingController.Scope;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

@Service @RequiredArgsConstructor
public class AdminJudgingService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final GroupService groups;
    private final AdminRejudgeImpact impacts;

    static ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,message);}
    static ApiException invalid(String message) {return new ApiException(HttpStatus.BAD_REQUEST,message);}
    static long number(Object value) {return ((Number)value).longValue();}
    static int offset(int page) {
        if (page<0 || page>Integer.MAX_VALUE/30) throw invalid("페이지 번호를 확인해 주세요.");
        return page*30;
    }
    // 제출 수나 제목 변경은 판정에 영향을 주지 않습니다. 실행 규격과 실제 테스트만 비교합니다.
    private static final String SETTINGS = """
        jsonb_build_object('execution_mode',p.execution_mode,'time_limit_ms',p.time_limit_ms,
            'memory_limit_mb',p.memory_limit_mb,'language_adjusted_time',p.language_adjusted_time,
            'function_abi_compatible',p.function_abi_compatible,'function_signature',p.function_signature::text)
        """;
    private static final String SUMMARY = """
        SELECT b.id,b.kind,b.state,p.problem_number AS "problemNumber",b.reason,b.created_at AS "createdAt",
            b.contest_id AS "contestId",c.group_id AS "groupId",u.username AS creator,
            (SELECT count(*) FROM admin_judge_jobs j WHERE j.batch_id=b.id) AS total,
            (SELECT count(*) FROM admin_judge_jobs j WHERE j.batch_id=b.id AND j.state='DONE') AS completed
        FROM admin_judge_batches b JOIN problems p ON p.id=b.problem_id JOIN users u ON u.id=b.creator_id
        LEFT JOIN group_contests c ON c.id=b.contest_id
        """;

    @Transactional public long create(AdminJudgingController.Create request, CustomUserDetails user) {
        ProblemService.requireOperator(user);
        boolean reference=request.kind()==AdminJudgingController.Kind.REFERENCE;
        if(reference && (request.submissionId()!=null||request.contestId()!=null||request.groupId()!=null))
            throw invalid("정답 코드 검증은 문제 번호로 실행합니다.");
        validateScope(new Scope(request.groupId(),request.contestId()),user);
        String reason=Objects.toString(request.reason(),"").strip();
        if (reason.isEmpty() && !reference) throw invalid("재채점 사유를 입력하세요.");
        // 새 배치의 시험 FK도 사용자/문제보다 먼저 잠가 채점 확정과 잠금 순서를 통일합니다.
        if (request.contestId()!=null) db.queryForList("SELECT id FROM group_contests WHERE id=? FOR KEY SHARE",request.contestId());
        db.queryForList("SELECT id FROM users WHERE id=? FOR KEY SHARE",user.getUserId());
        var rows=db.queryForList("SELECT p.id,"+SETTINGS+" AS config FROM problems p WHERE p.problem_number=? FOR UPDATE",request.problemNumber());
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        var p=rows.getFirst(); var pid=p.get("id");
        if(!db.queryForList("SELECT id FROM admin_judge_batches WHERE problem_id=? AND kind=? AND state='RUNNING'",pid,request.kind().name()).isEmpty())
            throw conflict("같은 문제의 검증이 이미 진행 중입니다.");
        long id=db.queryForObject("""
            INSERT INTO admin_judge_batches(kind,problem_id,contest_id,creator_id,reason,config)
            VALUES(?,?,?,?,?,?::jsonb) RETURNING id
            """,Long.class,request.kind().name(),pid,request.contestId(),user.getUserId(),reason.isEmpty()?"정답 코드 전체 검증":reason,p.get("config").toString());
        int hidden=db.update("""
            INSERT INTO admin_judge_inputs(batch_id,kind,display_order,case_id,input_data,expected_output,compare_type)
            SELECT ?,'HIDDEN',display_order,id,input_data,expected_output,compare_type FROM problem_test_cases
            WHERE problem_id=? AND is_active
            """,id,pid);
        if(hidden==0) throw conflict("활성 채점 테스트가 없습니다.");
        int count;
        if(reference) {
            db.update("""
                INSERT INTO admin_judge_inputs(batch_id,kind,display_order,input_data,expected_output)
                SELECT ?,'EXAMPLE',display_order,input_text,output_text FROM problem_examples WHERE problem_id=?
                """,id,pid);
            count=db.update("""
                INSERT INTO admin_judge_jobs(batch_id,language,reference_code)
                SELECT ?,language,source_code FROM reference_solutions WHERE problem_id=? ORDER BY language
                """,id,pid);
        } else {
            String filter=request.submissionId()==null?"":" AND s.id=?";
            var args=new ArrayList<Object>();Collections.addAll(args,id,pid,request.contestId());
            if(request.submissionId()!=null) args.add(request.submissionId());
            count=db.update("""
                INSERT INTO admin_judge_jobs(batch_id,submission_id,language,old_result)
                SELECT ?,s.id,s.language,jsonb_build_object('status',s.status,'score',s.score,'time',s.execution_time_ms,
                    'judged_at',s.judged_at,'diagnostic',s.diagnostic_json,'reused_from',s.reused_from_submission_id,
                    'context',s.judge_context_sha256,'cases',COALESCE((
                    SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id) FROM submission_test_case_results r WHERE r.submission_id=s.id),'[]'::jsonb))
                FROM submissions s WHERE s.problem_id=? AND s.contest_id IS NOT DISTINCT FROM ?::bigint
                    AND s.status NOT IN ('QUEUED','RUNNING')
                """+filter+" ORDER BY s.id",args.toArray());
        }
        if(count==0) throw conflict(reference?"등록된 정답 코드가 없습니다.":"선택한 범위에 판정이 끝난 제출이 없습니다.");
        return id;
    }

    private void validateScope(Scope scope,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if ((scope.groupId()==null)!=(scope.contestId()==null)) throw invalid("그룹과 시험 번호를 함께 입력하세요.");
        if (scope.contestId()!=null) {
            groups.requirePermission(scope.groupId(),user,GroupPermission.VIEW_CONTEST_RESULTS);
            if(db.queryForList("SELECT id FROM group_contests WHERE id=? AND group_id=?",scope.contestId(),scope.groupId()).isEmpty())
                throw new ApiException(HttpStatus.NOT_FOUND);
        }
    }
    @Transactional(readOnly=true) public List<Map<String,Object>> history(int page,Scope scope,CustomUserDetails user) {
        validateScope(scope,user);
        return db.queryForList(SUMMARY+" WHERE b.contest_id IS NOT DISTINCT FROM ?::bigint ORDER BY b.id DESC LIMIT 30 OFFSET ?",scope.contestId(),offset(page));
    }
    private Map<String,Object> batch(long id,Scope scope,CustomUserDetails user,boolean lock) {
        validateScope(scope,user);
        var rows=db.queryForList(SUMMARY+" WHERE b.id=? AND b.contest_id IS NOT DISTINCT FROM ?::bigint"+(lock?" FOR UPDATE OF b":""),id,scope.contestId());
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    @Transactional(readOnly=true) public Map<String,Object> detail(long id,int page,Scope scope,CustomUserDetails user) {
        var result=batch(id,scope,user,false);
        var jobs=db.queryForList("""
            SELECT j.id,j.submission_id AS "submissionId",j.language,j.state,j.verdict,
                j.old_result->>'status' AS "previousVerdict",j.elapsed_ms AS "timeMs",
                CASE WHEN b.kind='REFERENCE' THEN j.results::text END AS cases
            FROM admin_judge_jobs j JOIN admin_judge_batches b ON b.id=j.batch_id
            WHERE j.batch_id=? ORDER BY j.id LIMIT 30 OFFSET ?
            """,id,offset(page));
        for(var job:jobs) if(job.get("cases")!=null) job.put("cases",json.readTree((String)job.get("cases")));
        result.put("jobs",jobs);
        if("READY".equals(result.get("state")) && "REJUDGE".equals(result.get("kind"))) {
            var impact=impacts.preview(id);
            result.put("impact",impact);
            result.put("previewToken",token(impact));
        } else if("APPLIED".equals(result.get("state"))) {
            String impact=db.queryForObject("SELECT applied_impact::text FROM admin_judge_batches WHERE id=?",String.class,id);
            result.put("impact",json.readTree(impact));
        }
        return result;
    }
    private String token(Object impact) {return SubmissionService.sourceDigest(json.writeValueAsString(impact));}
    @Transactional public void cancel(long id,Scope scope,CustomUserDetails user) {
        var batch=batch(id,scope,user,true);
        if("CANCELLED".equals(batch.get("state"))) return;
        if(!List.of("RUNNING","READY").contains(batch.get("state"))) throw conflict("취소할 수 있는 작업이 아닙니다.");
        db.update("UPDATE admin_judge_batches SET state='CANCELLED',decided_by=?,decided_at=now() WHERE id=?",user.getUserId(),id);
    }
    @Transactional public void apply(long id,String expected,Scope scope,CustomUserDetails user) {
        var batch=batch(id,scope,user,true);
        if("APPLIED".equals(batch.get("state"))) return;
        if(!"READY".equals(batch.get("state")) || !"REJUDGE".equals(batch.get("kind"))) throw conflict("확정할 수 있는 미리보기가 없습니다.");
        // 시험의 다른 참가자가 다른 문제를 맞히는 경우도 순위를 바꾸므로 미리보기 확인부터 확정까지
        // 정식 채점과 같은 시험 → 사용자 → 문제 → 제출 순서로 직렬화합니다.
        if(scope.contestId()!=null) db.queryForList("SELECT id FROM group_contests WHERE id=? FOR UPDATE",scope.contestId());
        db.queryForList("""
            SELECT u.id FROM users u WHERE EXISTS (SELECT 1 FROM submissions s JOIN admin_judge_jobs j ON j.submission_id=s.id
            WHERE j.batch_id=? AND s.user_id=u.id) ORDER BY u.id FOR UPDATE
            """,id);
        long pid=db.queryForObject("SELECT p.id FROM problems p JOIN admin_judge_batches b ON b.problem_id=p.id WHERE b.id=? FOR UPDATE OF p",Long.class,id);
        db.queryForList("""
            SELECT s.id FROM submissions s JOIN admin_judge_jobs j ON j.submission_id=s.id
            WHERE j.batch_id=? ORDER BY s.id FOR UPDATE OF s
            """,id);
        boolean sameSettings=db.queryForObject("SELECT b.config=("+SETTINGS+") FROM problems p JOIN admin_judge_batches b ON b.problem_id=p.id WHERE b.id=?",Boolean.class,id);
        boolean sameCases=db.queryForObject("""
            WITH previous AS (SELECT case_id,display_order,input_data,expected_output,compare_type FROM admin_judge_inputs WHERE batch_id=? AND kind='HIDDEN'),
            current AS (SELECT id,display_order,input_data,expected_output,compare_type FROM problem_test_cases WHERE problem_id=? AND is_active)
            SELECT NOT EXISTS ((TABLE previous EXCEPT TABLE current) UNION ALL (TABLE current EXCEPT TABLE previous))
            """,Boolean.class,id,pid);
        if(!sameSettings||!sameCases) throw conflict("채점 규격 또는 테스트가 변경되었습니다. 미리보기를 다시 생성하세요.");
        int changed=db.queryForObject("""
            SELECT count(*) FROM submissions s JOIN admin_judge_jobs j ON j.submission_id=s.id WHERE j.batch_id=?
            AND (s.status<>j.old_result->>'status' OR s.judged_at IS DISTINCT FROM (j.old_result->>'judged_at')::timestamp)
            """,Integer.class,id);
        var impact=impacts.preview(id);
        if(changed!=0 || !token(impact).equals(expected)) throw conflict("기존 판정이나 점수가 달라졌습니다. 최신 결과를 다시 확인하세요.");
        impacts.apply(id,pid,impact);
        db.update("UPDATE admin_judge_batches SET state='APPLIED',decided_by=?,decided_at=now(),applied_impact=?::jsonb WHERE id=?",
                user.getUserId(),json.writeValueAsString(impact),id);
    }
}
