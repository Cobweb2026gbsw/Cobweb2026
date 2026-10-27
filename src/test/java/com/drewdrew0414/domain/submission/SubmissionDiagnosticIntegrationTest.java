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
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class SubmissionDiagnosticIntegrationTest {
    private static final String IMAGE="sha256:"+"e".repeat(64),ENGINE="f".repeat(64);
    @Autowired JdbcTemplate db;
    @Autowired SubmissionService submissions;

    @Test void safeDiagnosticIsPersistedAndOnlyAuthorizedDetailCanReadIt() {
        var fixture=fixture();var claim=running(fixture,"value=input()\nraise ValueError(value)\n");
        var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,(String)claim.get("source_code"),
            "  File \"/source/main.py\", line 2, in sol\nValueError: private-test-input",false);
        finish(claim,"RUNTIME_ERROR",diagnostic);
        long id=((Number)claim.get("id")).longValue();
        var detail=submissions.detail(id,fixture.user());
        assertEquals(diagnostic.publicValue(),detail.get("diagnostic"));
        assertFalse(detail.containsKey("diagnostic_json"));assertFalse(detail.containsKey("sourceCode"));
        assertFalse(db.queryForObject("SELECT diagnostic_json FROM submissions WHERE id=?",String.class,id).contains("private-test-input"));
        var denied=assertThrows(ResponseStatusException.class,()->submissions.detail(id,account()));
        assertEquals(404,denied.getStatusCode().value());
    }
    @Test void reusedErrorsCopyOnlySafeDiagnosticAndRespectTheNewLease() {
        var fixture=fixture();String source="raise ValueError('source-only')\n";
        var first=running(fixture,source);stamp(first);
        var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,source,"ValueError: private-input",false);
        finish(first,"RUNTIME_ERROR",diagnostic);
        var next=running(fixture,source);
        assertTrue(submissions.tryReuse(next,IMAGE,ENGINE));
        var detail=submissions.detail(((Number)next.get("id")).longValue(),fixture.user());
        assertEquals(diagnostic.publicValue(),detail.get("diagnostic"));
        assertEquals(((Number)first.get("id")).longValue(),((Number)detail.get("reusedFromSubmissionId")).longValue());
    }
    @Test void damagedStoredDataAndUnrelatedFinalStatusesNeverExposeADiagnostic() {
        var fixture=fixture();var claim=running(fixture,"print(1)\n");
        var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,"print(1)\n","ValueError: private",false);
        finish(claim,"ACCEPTED",diagnostic);
        long id=((Number)claim.get("id")).longValue();
        assertNull(db.queryForObject("SELECT diagnostic_json FROM submissions WHERE id=?",String.class,id));
        db.update("UPDATE submissions SET diagnostic_json=? WHERE id=?",diagnostic.toJson(),id);
        assertNull(submissions.detail(id,fixture.user()).get("diagnostic"));
        db.update("UPDATE submissions SET status='RUNTIME_ERROR',diagnostic_json=? WHERE id=?",
            "{\"phase\":\"RUN\",\"errorType\":\"PRIVATE_RAW_ERROR\",\"message\":\"secret\",\"locations\":[]}",id);
        assertNull(submissions.detail(id,fixture.user()).get("diagnostic"));
    }
    @Test void staleLeaseCannotOverwriteDiagnosticsAndDatabaseBoundsStoredJson() {
        var fixture=fixture();var claim=running(fixture,"pass\n");
        var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,"pass\n","ValueError: private",false);
        db.update("UPDATE submissions SET judge_token=? WHERE id=?",UUID.randomUUID().toString(),claim.get("id"));
        finish(claim,"RUNTIME_ERROR",diagnostic);
        assertNull(db.queryForObject("SELECT diagnostic_json FROM submissions WHERE id=?",String.class,claim.get("id")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->db.update(
            "UPDATE submissions SET diagnostic_json=JSON_OBJECT('message',?) WHERE id=?","x".repeat(8193),claim.get("id")));
    }
    @Test void endedContestAndRevokedParticipationCannotReadSafeDiagnosticThroughOrdinaryDetail() {
        var fixture=fixture();long contest=contest(fixture);
        long id=submissions.submitContest(contest,fixture.number(),"raise ValueError('source-only')\n",Language.PYTHON,fixture.user());
        db.update("UPDATE submissions SET status='RUNNING',judge_token=? WHERE id=?",UUID.randomUUID().toString(),id);
        var claim=db.queryForMap("SELECT * FROM submissions WHERE id=?",id);
        var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,"raise ValueError('source-only')\n","ValueError: private-input",false);
        finish(claim,"RUNTIME_ERROR",diagnostic);
        assertEquals(diagnostic.publicValue(),submissions.detail(id,fixture.user()).get("diagnostic"));
        db.update("UPDATE group_contests SET ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND),replay_allowed=FALSE WHERE id=?",contest);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->submissions.detail(id,fixture.user())).getStatusCode().value());
        db.update("UPDATE group_contests SET replay_allowed=TRUE WHERE id=?",contest);
        assertEquals(diagnostic.publicValue(),submissions.detail(id,fixture.user()).get("diagnostic"));
        db.update("UPDATE group_roles SET can_take_contests=FALSE WHERE group_id=(SELECT group_id FROM group_contests WHERE id=?)",contest);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->submissions.detail(id,fixture.user())).getStatusCode().value());
    }
    private void finish(Map<String,Object> claim,String status,JudgeDiagnostic diagnostic) {
        Long caseId="COMPILATION_ERROR".equals(status)?null:db.queryForObject("SELECT id FROM problem_test_cases WHERE problem_id=?",Long.class,claim.get("problem_id"));
        submissions.finish(claim,List.of(new SubmissionService.JudgeResult(caseId,status,3,diagnostic)));
    }
    private void stamp(Map<String,Object> claim) {
        claim.put("judge_image_id",IMAGE);claim.put("judge_engine",ENGINE);
        claim.put("judge_context",SubmissionService.contextDigest(submissions.problem(claim.get("problem_id")),submissions.cases(claim.get("problem_id")),IMAGE,ENGINE));
    }
    private Map<String,Object> running(Fixture fixture,String code) {
        long id=submissions.submit(fixture.number(),code,Language.PYTHON,fixture.user());
        db.update("UPDATE submissions SET status='RUNNING',judge_token=?,judge_attempts=1,claimed_at=CURRENT_TIMESTAMP(6),judge_heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=?",UUID.randomUUID().toString(),id);
        return db.queryForMap("SELECT * FROM submissions WHERE id=?",id);
    }
    private Fixture fixture() {
        var user=account();int number=ThreadLocalRandom.current().nextInt(1000000,2000000);
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
                status,visibility,author_id,execution_mode) VALUES(?,?,'body','input','output','PUBLISHED','PUBLIC',?,'STDIN')
            """,number,"Safe diagnostic "+UUID.randomUUID(),user.getUserId());
        long id=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
        db.update("INSERT INTO problem_languages(problem_id,language) VALUES(?,'PYTHON')",id);
        db.update("INSERT INTO problem_test_cases(problem_id,input_data,expected_output) VALUES(?,'private-test-input','private-test-answer')",id);
        return new Fixture(number,id,user);
    }
    private CustomUserDetails account() {
        String name="diag"+UUID.randomUUID().toString().replace("-","").substring(0,11);
        db.update("INSERT INTO users(username,password) VALUES(?,'diagnostic-test-only')",name);
        var entity=User.builder().username(name).password("diagnostic-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        return new CustomUserDetails(entity);
    }
    private long contest(Fixture fixture) {
        db.update("INSERT INTO study_groups(group_name,status) VALUES(?,'ACCEPTED')","dg"+UUID.randomUUID().toString().substring(0,12));
        long group=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO group_roles(group_id,role_name,builtin_key,can_take_contests,can_view_problems) VALUES(?,'member','MEMBER',TRUE,TRUE)",group);
        long role=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,1,'MEMBER',?)",fixture.user().getUserId(),group,role);
        db.update("UPDATE problems SET group_id=?,visibility='GROUP' WHERE id=?",group,fixture.id());
        db.update("INSERT INTO group_contests(group_id,title,starts_at,ends_at,created_by) VALUES(?,'diagnostic',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),?)",group,fixture.user().getUserId());
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        db.update("INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,1)",id,fixture.id());
        return id;
    }
    private record Fixture(int number,long id,CustomUserDetails user) {}
}
