package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

/** 별도 MySQL DB에서 원문 보존·캐시 scope·완전 비교·통계·fencing을 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SubmissionReuseIntegrationTest {
    private static final String IMAGE="sha256:"+"a".repeat(64), ENGINE="b".repeat(64);
    @Autowired JdbcTemplate db;
    @Autowired SubmissionService submissions;

    @Test void sourceIsPersistedUnchangedAndTheUtf8ByteBoundaryIsEnforced() {
        var f=fixture();
        String code="#"+"가".repeat(21845);
        assertEquals(65536,code.getBytes(StandardCharsets.UTF_8).length);
        long id=submissions.submit(f.number(),code,Language.PYTHON,f.user());
        assertEquals(code,db.queryForObject("SELECT source_code FROM submissions WHERE id=?",String.class,id));
        assertEquals(65536,db.queryForObject("SELECT OCTET_LENGTH(source_code) FROM submissions WHERE id=?",Integer.class,id));
        assertEquals(SubmissionService.sourceDigest(code),row(id).get("source_sha256"));
        for(String invalid:List.of(code+"x"," ","가".repeat(21846),"\ud800")) {
            var error=assertThrows(ResponseStatusException.class,()->submissions.submit(f.number(),invalid,Language.PYTHON,f.user()));
            assertEquals(400,error.getStatusCode().value());
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM submissions WHERE problem_id=?",Integer.class,f.problemId()));
    }

    @Test void exactOwnSubmissionReusesCompleteResultsButKeepsItsNewIdAndStatistics() {
        var f=fixture();String code="print('한글 é')\n";
        long original=complete(f,code,"ACCEPTED",null);
        var second=queued(f,code,null);
        long id=((Number)second.get("id")).longValue();
        assertNotEquals(original,id);
        assertTrue(submissions.tryReuse(second,IMAGE,ENGINE));
        assertEquals("ACCEPTED",row(id).get("status"));
        assertEquals(original,((Number)row(id).get("reused_from_submission_id")).longValue());
        assertNull(row(id).get("judge_token"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM submission_test_case_results WHERE submission_id=?",Integer.class,id));
        assertEquals(2,db.queryForObject("SELECT submit_count FROM users WHERE id=?",Integer.class,f.user().getUserId()));
        assertEquals(1,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,f.user().getUserId()));
        assertEquals(2,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,f.problemId()));
        assertEquals(original,((Number)submissions.detail(id,f.user()).get("reusedFromSubmissionId")).longValue());
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        assertEquals(2,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,f.problemId()));
    }

    @Test void databaseAlsoRejectsEmptyAndOverLimitUtf8SourcesWithoutTruncation() {
        var f=fixture();
        for(String code:List.of("","x".repeat(65537),"가".repeat(21846))) {
            var failure=assertThrows(org.springframework.dao.DataAccessException.class,()->db.update(
                "INSERT INTO submissions(user_id,problem_id,language,source_code) VALUES(?,?,'PYTHON',?)",
                f.user().getUserId(),f.problemId(),code));
            // MySQL CHECK 위반 3819는 Connector/J에서 HY000으로 전달될 수 있습니다.
            var sql=assertInstanceOf(java.sql.SQLException.class,failure.getMostSpecificCause());
            assertEquals(3819,sql.getErrorCode());
            assertTrue(sql.getMessage().contains("chk_submissions_source_bytes"));
        }
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM submissions WHERE problem_id=?",Integer.class,f.problemId()));
    }

    @Test void sourceHashCollisionCaseDifferencesWhitespaceAndUnicodeNormalizationDoNotMatch() {
        var f=fixture();String first="print('é')";
        long original=complete(f,first,"ACCEPTED",null);
        for(String other:List.of("PRINT('É')",first+"\n",first+" ","print('e\u0301')")) {
            var second=queued(f,other,null);
            // 실제 해시 충돌이 생겨도 최종 binary 원문 비교에서 거절하는지 강제로 확인합니다.
            db.update("UPDATE submissions SET source_sha256=? WHERE id=?",SubmissionService.sourceDigest(other),original);
            assertFalse(submissions.tryReuse(second,IMAGE,ENGINE),other);
            db.update("UPDATE submissions SET status='SYSTEM_ERROR',judge_token=NULL WHERE id=?",second.get("id"));
        }
    }

    @Test void differentOwnerLanguageImageEngineLimitsOrTestsDisableReuse() {
        var f=fixture();String code="print(2)";
        complete(f,code,"ACCEPTED",null);
        var second=queued(f,code,null);
        assertFalse(submissions.tryReuse(second,"sha256:"+"c".repeat(64),ENGINE));
        assertFalse(submissions.tryReuse(second,IMAGE,"d".repeat(64)));
        db.update("UPDATE problems SET time_limit_ms=time_limit_ms+1 WHERE id=?",f.problemId());
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        db.update("UPDATE problems SET time_limit_ms=time_limit_ms-1,memory_limit_mb=512 WHERE id=?",f.problemId());
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        db.update("UPDATE problems SET memory_limit_mb=256 WHERE id=?",f.problemId());
        db.update("UPDATE problem_test_cases SET expected_output='changed' WHERE problem_id=?",f.problemId());
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        db.update("UPDATE problem_test_cases SET expected_output='2' WHERE problem_id=?",f.problemId());
        var other=otherUser();
        long otherId=submissions.submit(f.number(),code,Language.PYTHON,other);
        assertFalse(submissions.tryReuse(running(otherId),IMAGE,ENGINE));
        db.update("UPDATE submissions SET language='RUBY' WHERE id=?",second.get("id"));
        second.put("language","RUBY");
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
    }

    @Test void unfinishedSystemErrorIncompleteAndLostLeaseRecordsAreNeverReused() {
        var f=fixture();String code="print(2)";
        long original=complete(f,code,"ACCEPTED",null);
        var second=queued(f,code,null);
        for(String status:List.of("QUEUED","RUNNING","SYSTEM_ERROR")) {
            db.update("UPDATE submissions SET status=? WHERE id=?",status,original);
            assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        }
        db.update("UPDATE submissions SET status='ACCEPTED' WHERE id=?",original);
        second.put("judge_token","stale-token");
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
        second.put("judge_token",row(((Number)second.get("id")).longValue()).get("judge_token"));
        db.update("DELETE FROM submission_test_case_results WHERE submission_id=?",original);
        assertFalse(submissions.tryReuse(second,IMAGE,ENGINE));
    }

    @Test void changedContextDuringRealJudgingNeverBecomesAReusableEntry() {
        var f=fixture();var pending=queued(f,"print(2)",null);
        stamp(pending);
        db.update("UPDATE problem_test_cases SET input_data='different' WHERE problem_id=?",f.problemId());
        finish(pending,"ACCEPTED");
        assertEquals("ACCEPTED",row(((Number)pending.get("id")).longValue()).get("status"));
        assertNull(row(((Number)pending.get("id")).longValue()).get("judge_context_sha256"));
    }

    @Test void contestReuseIsScopedToThatContestAndRechecksCurrentReadPermission() {
        var f=fixture();long contest=contest(f);
        long original=complete(f,"print(2)","ACCEPTED",contest);
        var second=queued(f,"print(2)",contest);
        assertTrue(submissions.tryReuse(second,IMAGE,ENGINE));
        assertEquals(original,((Number)row(((Number)second.get("id")).longValue()).get("reused_from_submission_id")).longValue());
        assertEquals(0,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,f.user().getUserId()));
        assertEquals(0,db.queryForObject("SELECT submit_count FROM users WHERE id=?",Integer.class,f.user().getUserId()));
        long differentContest=additionalContest(f,contest);
        assertFalse(submissions.tryReuse(queued(f,"print(2)",differentContest),IMAGE,ENGINE));
        var denied=queued(f,"print(2)",contest);
        db.update("UPDATE group_roles SET can_take_contests=FALSE WHERE group_id=(SELECT group_id FROM group_contests WHERE id=?)",contest);
        assertFalse(submissions.tryReuse(denied,IMAGE,ENGINE));
    }

    @Test void finishedContestDoesNotReuseAHiddenPersonalResultEvenWhenStillQueued() {
        var f=fixture();long contest=contest(f);
        complete(f,"print(2)","ACCEPTED",contest);
        var pending=queued(f,"print(2)",contest);
        db.update("UPDATE group_contests SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 2 HOUR),ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),replay_allowed=FALSE WHERE id=?",contest);
        assertFalse(submissions.tryReuse(pending,IMAGE,ENGINE));
    }

    private Fixture fixture() {
        var user=otherUser();int number=-ThreadLocalRandom.current().nextInt(100000,1000000000);
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,author_id,status,visibility,execution_mode)
            VALUES(?,?,'body','input','output',?,'PUBLISHED','PUBLIC','STDIN')
            """,number,"Cache "+UUID.randomUUID(),user.getUserId());
        long pid=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
        db.update("INSERT INTO problem_languages(problem_id,language) VALUES(?,'PYTHON')",pid);
        db.update("INSERT INTO problem_test_cases(problem_id,input_data,expected_output,display_order) VALUES(?,'1','2',1)",pid);
        return new Fixture(number,pid,user);
    }
    private CustomUserDetails otherUser() {
        String name="r"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,'unused')",name);
        long id=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        var user=User.builder().username(name).password("unused").build();ReflectionTestUtils.setField(user,"id",id);
        return new CustomUserDetails(user);
    }
    private Map<String,Object> queued(Fixture f,String code,Long contest) {
        long id=contest==null?submissions.submit(f.number(),code,Language.PYTHON,f.user()):
            submissions.submitContest(contest,f.number(),code,Language.PYTHON,f.user());
        return running(id);
    }
    private Map<String,Object> running(long id) {
        db.update("UPDATE submissions SET status='RUNNING',judge_token=?,judge_attempts=1,claimed_at=CURRENT_TIMESTAMP(6),judge_heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=?",UUID.randomUUID().toString(),id);
        return row(id);
    }
    private long complete(Fixture f,String code,String status,Long contest) {
        var pending=queued(f,code,contest);stamp(pending);finish(pending,status);return ((Number)pending.get("id")).longValue();
    }
    private void stamp(Map<String,Object> pending) {
        pending.put("judge_image_id",IMAGE);pending.put("judge_engine",ENGINE);
        pending.put("judge_context",SubmissionService.contextDigest(submissions.problem(pending.get("problem_id")),submissions.cases(pending.get("problem_id")),IMAGE,ENGINE));
    }
    private void finish(Map<String,Object> pending,String status) {
        var test=submissions.cases(pending.get("problem_id")).getFirst();
        submissions.finish(pending,List.of(new SubmissionService.JudgeResult(((Number)test.get("id")).longValue(),status,8)));
    }
    private Map<String,Object> row(long id) { return db.queryForMap("SELECT * FROM submissions WHERE id=?",id); }
    private long contest(Fixture f) {
        db.update("INSERT INTO study_groups(group_name,status) VALUES(?,'ACCEPTED')","c"+UUID.randomUUID().toString().replace("-","").substring(0,12));
        long gid=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO group_roles(group_id,role_name,builtin_key,can_take_contests,can_view_problems) VALUES(?,'member','MEMBER',TRUE,TRUE)",gid);
        long role=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,1,'MEMBER',?)",f.user().getUserId(),gid,role);
        db.update("UPDATE problems SET group_id=?,visibility='GROUP' WHERE id=?",gid,f.problemId());
        db.update("INSERT INTO group_contests(group_id,title,starts_at,ends_at,created_by) VALUES(?,'cache',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),?)",gid,f.user().getUserId());
        long cid=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,1)",cid,f.problemId());
        return cid;
    }
    private long additionalContest(Fixture f,long prior) {
        db.update("INSERT INTO group_contests(group_id,title,starts_at,ends_at,created_by) SELECT group_id,'other',starts_at,ends_at,created_by FROM group_contests WHERE id=?",prior);
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,1)",id,f.problemId());return id;
    }
    private record Fixture(int number,long problemId,CustomUserDetails user) {}
}
