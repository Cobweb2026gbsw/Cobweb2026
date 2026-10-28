package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 제한에 걸린 비공개 요청은 실제 케이스를 읽거나 파싱하지 않는지 외부 DB 없이 확인합니다. */
class TestRunCapacityTest {
    private static final long USER_ID=17L,PROBLEM_ID=23L;
    private static final int NUMBER=1000;
    private static final String USER_PENDING="SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND status IN ('QUEUED','RUNNING')";
    private static final String ALL_PENDING="SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')";
    private static final String USER_RECENT="SELECT COUNT(*) FROM user_test_runs WHERE user_id=? AND created_at>((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 minutes')";

    @Test void alreadyPendingTwoRejectsBeforeReadingOrValidatingAnyPrivateCase() {
        var fixture=fixture();when(fixture.db().queryForObject(USER_PENDING,Long.class,USER_ID)).thenReturn(2L);
        assertPrivateCapacityRejected(fixture);
        verify(fixture.db(),never()).queryForObject(ALL_PENDING,Long.class);
        verify(fixture.db(),never()).queryForObject(USER_RECENT,Long.class,USER_ID);
    }

    @Test void fullGlobalQueueAlsoRejectsBeforePrivateCaseAccess() {
        var fixture=fixture();when(fixture.db().queryForObject(ALL_PENDING,Long.class)).thenReturn(60L);
        assertPrivateCapacityRejected(fixture);
        verify(fixture.db(),never()).queryForObject(USER_RECENT,Long.class,USER_ID);
    }

    @Test void perMinuteLimitAlsoRejectsBeforePrivateCaseAccess() {
        var fixture=fixture();when(fixture.db().queryForObject(USER_RECENT,Long.class,USER_ID)).thenReturn(10L);
        assertPrivateCapacityRejected(fixture);
    }

    @Test void manualInputValidationStillPrecedesCapacityAndDoesNotReadPrivateCases() {
        var fixture=fixture();fixture.problem().put("function_signature",null);
        when(fixture.db().queryForObject(USER_PENDING,Long.class,USER_ID)).thenReturn(2L);
        var request=new TestRunService.Request("PYTHON","def sol(value):\n    return value\n",
            List.of(new TestRunService.Case("x".repeat(65537),"1")));
        assertEquals(400,assertThrows(ApiException.class,()->fixture.service().create(NUMBER,request,fixture.user())).getStatusCode().value());
        verify(fixture.db(),never()).queryForObject(USER_PENDING,Long.class,USER_ID);
        verifyNoInteractions(fixture.json());assertNoPrivateReadOrInsert(fixture.db());
    }

    private void assertPrivateCapacityRejected(Fixture fixture) {
        var request=new TestRunService.Request("PYTHON","def sol(value):\n    return value\n",null,true);
        assertEquals(429,assertThrows(ApiException.class,()->fixture.service().create(NUMBER,request,fixture.user())).getStatusCode().value());
        verify(fixture.problems()).load(NUMBER,fixture.user());
        // 같은 JSON 엔진이 서명 파싱·케이스 타입 검증·스냅샷 직렬화를 맡으므로 아예 접촉하지 않아야 합니다.
        verifyNoInteractions(fixture.json());assertNoPrivateReadOrInsert(fixture.db());
        assertTrue(mockingDetails(fixture.db()).getInvocations().stream().anyMatch(call->
            call.getArguments().length>0 && call.getArgument(0) instanceof String sql && sql.contains("UPDATE user_test_runs SET status='SYSTEM_ERROR'")));
    }

    private void assertNoPrivateReadOrInsert(JdbcTemplate db) {
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(call->
            call.getArguments().length>0 && call.getArgument(0) instanceof String sql &&
                (sql.contains("problem_test_cases") || sql.contains("user_test_run_private_cases") || sql.contains("INSERT INTO"))));
    }

    private Fixture fixture() {
        var db=mock(JdbcTemplate.class);var problems=mock(ProblemService.class);var json=spy(new ObjectMapper());
        var user=mock(CustomUserDetails.class);when(user.isEnabled()).thenReturn(true);when(user.getUserId()).thenReturn(USER_ID);
        when(db.queryForList("SELECT status FROM users WHERE id=? FOR UPDATE",USER_ID)).thenReturn(List.of(Map.of("status","ACTIVE")));
        when(db.queryForObject("SELECT id FROM user_test_run_queue_lock WHERE id=1 FOR UPDATE",Integer.class)).thenReturn(1);
        when(db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR SHARE",NUMBER)).thenReturn(List.of(Map.of("id",PROBLEM_ID)));
        var problem=new HashMap<String,Object>();problem.put("id",PROBLEM_ID);problem.put("execution_mode","FUNCTION");
        problem.put("time_limit_ms",2000);problem.put("memory_limit_mb",256);
        problem.put("function_signature","{\"name\":\"sol\",\"parameters\":[{\"name\":\"value\",\"type\":\"int\"}],\"returnType\":\"int\"}");
        when(problems.load(NUMBER,user)).thenReturn(problem);
        when(db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,PROBLEM_ID)).thenReturn(0L);
        when(db.queryForObject("SELECT COUNT(*) FROM problem_languages WHERE problem_id=? AND language=?",Long.class,PROBLEM_ID,"PYTHON")).thenReturn(1L);
        when(db.queryForObject(USER_PENDING,Long.class,USER_ID)).thenReturn(0L);
        when(db.queryForObject(ALL_PENDING,Long.class)).thenReturn(0L);
        when(db.queryForObject(USER_RECENT,Long.class,USER_ID)).thenReturn(0L);
        return new Fixture(db,problems,json,user,problem,new TestRunService(db,problems,json));
    }
    private record Fixture(JdbcTemplate db,ProblemService problems,ObjectMapper json,CustomUserDetails user,
                           Map<String,Object> problem,TestRunService service) {}
}
