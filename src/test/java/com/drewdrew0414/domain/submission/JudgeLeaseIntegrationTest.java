package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 PostgreSQL에서 lease 회수와 오래된 토큰의 결과 반영 차단을 검증합니다. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JudgeLeaseIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired SubmissionService submissions;

    @Test void aLiveHeartbeatPreservesAClaimEvenWhenItsOriginalClaimIsOld() {
        var fixture=runningSubmission(1);
        db.update("UPDATE submissions SET claimed_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '21 minute') WHERE id=?",fixture.id());

        var claimed=submission(fixture.id());
        java.time.LocalDateTime before=dateTime(claimed.get("judge_heartbeat_at"));
        assertTrue(submissions.heartbeat(claimed));
        submissions.recoverExpired();

        var after=submission(fixture.id());
        assertEquals("RUNNING",after.get("status"));
        assertEquals(fixture.token(),after.get("judge_token"));
        assertEquals(1,((Number)after.get("judge_attempts")).intValue());
        assertTrue(dateTime(after.get("judge_heartbeat_at")).isAfter(before));
    }

    @Test void anExpiredClaimIsRetriedAndItsOldTokenCannotPublishResults() {
        var fixture=runningSubmission(1);
        expire(fixture.id());
        var stale=submission(fixture.id());

        submissions.recoverExpired();
        var queued=submission(fixture.id());
        assertEquals("QUEUED",queued.get("status"));
        assertNull(queued.get("judge_token"));
        assertNull(queued.get("judge_heartbeat_at"));
        assertFalse(submissions.heartbeat(stale));

        String nextToken=UUID.randomUUID().toString();
        db.update("""
            UPDATE submissions SET status='RUNNING',judge_token=?,judge_attempts=judge_attempts+1,
                claimed_at=(clock_timestamp() AT TIME ZONE 'UTC'),judge_heartbeat_at=(clock_timestamp() AT TIME ZONE 'UTC') WHERE id=?
            """,nextToken,fixture.id());
        var current=submission(fixture.id());
        assertEquals(2,((Number)current.get("judge_attempts")).intValue());
        var accepted=List.of(new SubmissionService.JudgeResult(null,"ACCEPTED",12));

        submissions.finish(stale,accepted);
        assertEquals("RUNNING",submission(fixture.id()).get("status"));
        assertEquals(0,caseResults(fixture.id()));
        assertEquals(0,acceptedCount(fixture.problemId()));

        submissions.finish(current,accepted);
        submissions.finish(current,accepted);
        assertEquals("ACCEPTED",submission(fixture.id()).get("status"));
        assertEquals(1,caseResults(fixture.id()));
        assertEquals(1,acceptedCount(fixture.problemId()));
        assertEquals(1,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,fixture.userId()));
    }

    @Test void aThirdExpiredAttemptBecomesSystemErrorWithoutPublishingStaleResults() {
        var fixture=runningSubmission(3);
        expire(fixture.id());
        var stale=submission(fixture.id());

        submissions.recoverExpired();
        var finished=submission(fixture.id());
        assertEquals("SYSTEM_ERROR",finished.get("status"));
        assertEquals(3,((Number)finished.get("judge_attempts")).intValue());
        assertNull(finished.get("judge_token"));
        assertNotNull(finished.get("judged_at"));
        assertFalse(submissions.heartbeat(stale));
        submissions.finish(stale,List.of(new SubmissionService.JudgeResult(null,"ACCEPTED",1)));
        assertEquals(0,caseResults(fixture.id()));
        assertEquals(0,acceptedCount(fixture.problemId()));
    }

    @Test void legacyRunningRowsWithoutAHeartbeatUseClaimedAndThenSubmittedTime() {
        var claimed=runningSubmission(1);
        db.update("""
            UPDATE submissions SET judge_heartbeat_at=NULL,
                claimed_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '21 minute')
            WHERE id=?
            """,claimed.id());
        var submitted=runningSubmission(3);
        db.update("""
            UPDATE submissions SET judge_heartbeat_at=NULL,claimed_at=NULL,
                submitted_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '21 minute')
            WHERE id=?
            """,submitted.id());

        submissions.recoverExpired();

        assertEquals("QUEUED",submission(claimed.id()).get("status"));
        assertNull(submission(claimed.id()).get("judge_token"));
        assertEquals("SYSTEM_ERROR",submission(submitted.id()).get("status"));
        assertNotNull(submission(submitted.id()).get("judged_at"));
    }

    private Fixture runningSubmission(int attempts) {
        String name="j"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,'unused')",name);
        long userId=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        int number=-ThreadLocalRandom.current().nextInt(100000,1000000000);
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
                author_id,status,visibility,submission_count)
            VALUES(?,?,'body','input','output',?,'PUBLISHED','PUBLIC',1)
            """,number,"Lease test "+UUID.randomUUID(),userId);
        long problemId=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
        String token=UUID.randomUUID().toString();
        long id=db.queryForObject("""
            INSERT INTO submissions(user_id,problem_id,language,source_code,status,judge_token,claimed_at,
                judge_heartbeat_at,judge_attempts)
            VALUES(?,?,'PYTHON','print(1)','RUNNING',?,(clock_timestamp() AT TIME ZONE 'UTC'),(clock_timestamp() AT TIME ZONE 'UTC'),?)
            RETURNING id
            """,Long.class,userId,problemId,token,attempts);
        return new Fixture(id,userId,problemId,token);
    }

    private void expire(long id) {
        db.update("UPDATE submissions SET judge_heartbeat_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '21 minute') WHERE id=?",id);
    }
    private Map<String,Object> submission(long id) {
        return db.queryForMap("SELECT * FROM submissions WHERE id=?",id);
    }
    private java.time.LocalDateTime dateTime(Object value) {
        // JDBC Timestamp와 LocalDateTime 표현을 모두 지원합니다.
        return value instanceof Timestamp t?t.toLocalDateTime():(java.time.LocalDateTime)value;
    }
    private int caseResults(long id) {
        return db.queryForObject("SELECT COUNT(*) FROM submission_test_case_results WHERE submission_id=?",Integer.class,id);
    }
    private int acceptedCount(long problemId) {
        return db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,problemId);
    }
    private record Fixture(long id,long userId,long problemId,String token) {}
}
