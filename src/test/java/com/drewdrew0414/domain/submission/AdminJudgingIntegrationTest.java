package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class AdminJudgingIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired AdminJudgingService service;
    @Autowired AdminJudgeQueue queue;
    @Autowired SubmissionService submissions;
    @Autowired DockerJudge runner;
    @Autowired ObjectMapper json;
    final AdminJudgingController.Scope general=new AdminJudgingController.Scope(null,null);
    long pid,caseId; int number;
    CustomUserDetails admin,student;
    @BeforeEach void setup() {
        admin=account(Role.OPERATOR);student=account(Role.USER);
        number=ThreadLocalRandom.current().nextInt(2000000,3000000);
        pid=db.queryForObject("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id)
            VALUES(?,?,'문제','입력','출력',?) RETURNING id
            """,Long.class,number,"재채점"+number,admin.getUserId());
        caseId=db.queryForObject("INSERT INTO problem_test_cases(problem_id,input_data,expected_output) VALUES(?,'hidden','hidden') RETURNING id",Long.class,pid);
        db.update("INSERT INTO problem_examples(problem_id,input_text,output_text) VALUES(?,'sample','sample')",pid);
        db.update("INSERT INTO reference_solutions(problem_id,language,file_name,source_code) VALUES(?,'PYTHON','s.py','print(input())')",pid);
    }
    private CustomUserDetails account(Role role) {
        String name="admj"+UUID.randomUUID().toString().substring(0,9);
        var user=User.builder().username(name).password("test-password").build();
        long id=db.queryForObject("INSERT INTO users(username,password,role) VALUES(?,'ignored',?) RETURNING id",Long.class,name,role.name());
        ReflectionTestUtils.setField(user,"id",id);ReflectionTestUtils.setField(user,"role",role);
        return new CustomUserDetails(user);
    }
    private AdminJudgingController.Create request(Long submission) {
        return new AdminJudgingController.Create(number,AdminJudgingController.Kind.REJUDGE,submission,null,null,"잘못된 예전 기준 정정");
    }
    private long submission(Long contest,String verdict) {
        if(contest==null) db.update("UPDATE problems SET submission_count=submission_count+1 WHERE id=?",pid);
        return db.queryForObject("""
            INSERT INTO submissions(problem_id,user_id,contest_id,language,source_code,status,judged_at)
            VALUES(?,?,?,'PYTHON','print(input())',?,now() AT TIME ZONE 'UTC') RETURNING id
            """,Long.class,pid,student.getUserId(),contest,verdict);
    }
    private void complete(String verdict) {
        var claim=queue.claim(); assertNotNull(claim);assertTrue(queue.renew(claim));
        queue.finish(claim,List.of(new AdminJudgeWorker.CaseResult("HIDDEN",1,caseId,verdict,8,null,null)),"immutable-test-image",null);
    }
    @Test void previewAndApplyCreditOnceThenRevokeOnlyLastSolve() {
        long sid=submission(null,"WRONG_ANSWER"),batch=service.create(request(sid),admin);
        complete("ACCEPTED");var detail=service.detail(batch,0,general,admin);
        assertEquals("WRONG_ANSWER",verdict(sid));assertEquals(0,points());
        assertFalse(((Map<?,?>)detail.get("impact")).get("points").equals(List.of()));
        String token=(String)detail.get("previewToken");
        service.apply(batch,token,general,admin);service.apply(batch,token,general,admin);
        assertEquals("ACCEPTED",verdict(sid));assertEquals(50,points());
        long second=submission(null,"WRONG_ANSWER");
        long secondBatch=service.create(request(second),admin);complete("ACCEPTED");
        service.apply(secondBatch,(String)service.detail(secondBatch,0,general,admin).get("previewToken"),general,admin);
        long reverse=service.create(request(sid),admin);complete("WRONG_ANSWER");
        service.apply(reverse,(String)service.detail(reverse,0,general,admin).get("previewToken"),general,admin);
        assertEquals(50,points());
        long last=service.create(request(second),admin);complete("WRONG_ANSWER");
        service.apply(last,(String)service.detail(last,0,general,admin).get("previewToken"),general,admin);
        assertEquals(0,points());
        assertEquals(0,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,pid));
        assertEquals("ACCEPTED",db.queryForObject("SELECT old_result->>'status' FROM admin_judge_jobs WHERE batch_id=?",String.class,last));
    }
    @Test void rejudgingOneSubmissionInvalidatesItsExactCacheFamilyButKeepsCopiedHistory() {
        String source="print(input())",image="sha256:"+"e".repeat(64),engine="f".repeat(64);
        String context=SubmissionService.contextDigest(submissions.problem(pid),submissions.cases(pid),image,engine);
        String digest=SubmissionService.sourceDigest(source);
        long first=submission(null,"RUNNING");
        var claim=db.queryForMap("SELECT * FROM submissions WHERE id=?",first);
        claim.put("judge_image_id",image);claim.put("judge_engine",engine);claim.put("judge_context",context);
        db.update("UPDATE submissions SET source_sha256=? WHERE id=?",digest,first);
        submissions.finish(claim,List.of(new SubmissionService.JudgeResult(caseId,"ACCEPTED",8)));
        long copy=submission(null,"RUNNING");
        db.update("UPDATE submissions SET source_sha256=? WHERE id=?",digest,copy);
        assertTrue(submissions.tryReuse(db.queryForMap("SELECT * FROM submissions WHERE id=?",copy),image,engine));
        long differentUser=submission(null,"WRONG_ANSWER"),differentSource=submission(null,"WRONG_ANSWER"),
            differentLanguage=submission(null,"WRONG_ANSWER"),differentContext=submission(null,"WRONG_ANSWER");
        long group=db.queryForObject("INSERT INTO study_groups(group_name,status) VALUES(?,'ACCEPTED') RETURNING id",Long.class,"jg"+number);
        long contest=db.queryForObject("""
            INSERT INTO group_contests(group_id,title,starts_at,ends_at,created_by)
            VALUES(?,'cached',now()-interval '1h',now()+interval '1h',?) RETURNING id
            """,Long.class,group,admin.getUserId());
        db.update("INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,1)",contest,pid);
        long differentContest=submission(contest,"WRONG_ANSWER");
        db.update("UPDATE submissions SET user_id=? WHERE id=?",admin.getUserId(),differentUser);
        // 고의로 동일 해시를 저장해도 다른 코드 바이트는 캐시 자격을 잃지 않아야 합니다.
        db.update("UPDATE submissions SET source_code=source_code||' ' WHERE id=?",differentSource);
        db.update("UPDATE submissions SET language='CPP' WHERE id=?",differentLanguage);
        db.update("UPDATE submissions SET judge_context_sha256=?,source_sha256=? WHERE id IN (?,?,?,?,?)",context,digest,
            differentUser,differentSource,differentLanguage,differentContext,differentContest);
        String otherContext="d".repeat(64);
        db.update("UPDATE submissions SET judge_context_sha256=? WHERE id=?",otherContext,differentContext);
        long batch=service.create(request(first),admin); complete("WRONG_ANSWER");
        service.apply(batch,(String)service.detail(batch,0,general,admin).get("previewToken"),general,admin);
        assertEquals("WRONG_ANSWER",verdict(first));assertEquals("ACCEPTED",verdict(copy));
        assertEquals(50,points());assertEquals(first,db.queryForObject("SELECT reused_from_submission_id FROM submissions WHERE id=?",Long.class,copy));
        assertNull(db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,copy));
        for(long retained:List.of(differentUser,differentSource,differentLanguage,differentContest))
            assertEquals(context,db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,retained));
        assertEquals(otherContext,db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,differentContext));
        long third=submission(null,"RUNNING");
        assertFalse(submissions.tryReuse(db.queryForMap("SELECT * FROM submissions WHERE id=?",third),image,engine));
        assertEquals("RUNNING",verdict(third));
    }
    @Test void denyOutsideOperatorAndRejectStaleTestSnapshots() {
        var req=request(submission(null,"WRONG_ANSWER"));
        assertThrows(ResponseStatusException.class,()->service.create(req,student));
        long batch=service.create(req,admin);complete("ACCEPTED");
        assertThrows(ResponseStatusException.class,()->service.detail(batch,0,general,student));
        db.update("UPDATE problem_test_cases SET expected_output='changed' WHERE id=?",caseId);
        assertThrows(ResponseStatusException.class,()->service.apply(batch,(String)service.detail(batch,0,general,admin).get("previewToken"),general,admin));
        assertEquals("WRONG_ANSWER",verdict(req.submissionId()));
        service.cancel(batch,general,admin);assertEquals("CANCELLED",service.detail(batch,0,general,admin).get("state"));
    }
    @Test void contestPreviewFollowsScopeAndKeepsPersonalPointsUntouched() {
        long group=db.queryForObject("INSERT INTO study_groups(group_name,status) VALUES(?,'ACCEPTED') RETURNING id",Long.class,"jg"+number);
        long contest=db.queryForObject("""
            INSERT INTO group_contests(group_id,title,starts_at,ends_at,created_by)
            VALUES(?,'ct',now()-interval '1h',now()+interval '1h',?) RETURNING id
            """,Long.class,group,admin.getUserId());
        db.update("INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,1)",contest,pid);
        long sid=submission(contest,"WRONG_ANSWER"),later=submission(contest,"ACCEPTED");
        db.update("""
            UPDATE submissions s SET submitted_at=c.starts_at+CASE WHEN s.id=? THEN interval '30 seconds' ELSE interval '90 seconds' END
            FROM group_contests c WHERE c.id=s.contest_id AND s.id IN (?,?)
            """,sid,sid,later);
        var req=new AdminJudgingController.Create(number,AdminJudgingController.Kind.REJUDGE,sid,group,contest,"시험 데이터 수정");
        var scope=new AdminJudgingController.Scope(group,contest);
        long batch=service.create(req,admin); complete("ACCEPTED");
        assertTrue(service.history(0,general,admin).isEmpty());
        assertEquals(batch,service.history(0,scope,admin).getFirst().get("id"));
        assertThrows(ResponseStatusException.class,()->service.detail(batch,0,general,admin));
        var preview=service.detail(batch,0,scope,admin);
        var standings=(List<?>)((Map<?,?>)preview.get("impact")).get("standings");
        var standing=(Map<?,?>)standings.getFirst();
        assertEquals(standing.get("beforeRank"),standing.get("afterRank"));
        assertEquals(standing.get("beforeSolved"),standing.get("afterSolved"));
        assertEquals(90,((Number)standing.get("beforeSeconds")).longValue());
        assertEquals(30,((Number)standing.get("afterSeconds")).longValue());
        service.apply(batch,(String)preview.get("previewToken"),scope,admin);
        assertEquals("ACCEPTED",verdict(sid));assertEquals(0,points());
        assertThrows(ResponseStatusException.class,()->service.create(request(sid),admin));
    }
    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void realRunnerValidatesPublicAndPrivateCasesAndRejudgesStoredCode() {
        var worker=new AdminJudgeWorker(queue,runner,json);
        long ref=service.create(new AdminJudgingController.Create(number,AdminJudgingController.Kind.REFERENCE,null,null,null,null),admin);
        worker.tick();
        var detail=service.detail(ref,0,general,admin);
        var job=(Map<?,?>)((List<?>)detail.get("jobs")).getFirst();
        assertEquals("ACCEPTED",job.get("verdict"));
        assertEquals(2,json.valueToTree(job.get("cases")).size());
        assertEquals("sample",json.valueToTree(job.get("cases")).get(0).get("actual").asString().strip());
        assertEquals("hidden",json.valueToTree(job.get("cases")).get(1).get("actual").asString().strip());
        long sid=submission(null,"WRONG_ANSWER"),batch=service.create(request(sid),admin);
        worker.tick();var preview=service.detail(batch,0,general,admin);
        assertEquals("READY",preview.get("state"));assertEquals("WRONG_ANSWER",verdict(sid));
        service.apply(batch,(String)preview.get("previewToken"),general,admin);assertEquals("ACCEPTED",verdict(sid));
    }
    private String verdict(long sid){return db.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,sid);}
    private long points(){return db.queryForObject("SELECT rank_points FROM users WHERE id=?",Long.class,student.getUserId());}
}
