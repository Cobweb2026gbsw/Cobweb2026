package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SubmissionContestTest {
    @Test void submissionRechecksTimeAtomicallyAtInsertInsteadOfUsingAnEarlierDecision() {
        var db=mock(JdbcTemplate.class);
        var problems=mock(ProblemService.class);
        var contests=mock(ContestService.class);
        var user=User.builder().username("candidate").password("unused").build();
        ReflectionTestUtils.setField(user,"id",12L);
        var details=new CustomUserDetails(user);
        var p=new HashMap<String,Object>(Map.of("id",7L,"execution_mode","STDIN","status","PUBLISHED"));
        when(db.queryForList(contains("FROM group_contests"),eq(3L))).thenReturn(List.of(Map.of("id",3L)));
        when(db.queryForList(contains("SELECT * FROM problems"),eq(1001))).thenReturn(List.of(p));
        when(db.queryForObject(contains("problem_languages"),eq(Integer.class),eq(7L),eq("PYTHON"))).thenReturn(1);
        when(db.queryForObject(contains("problem_test_cases"),eq(Long.class),eq(7L))).thenReturn(1L);
        when(db.queryForObject(contains("status IN"),eq(Long.class),eq(12L))).thenReturn(0L);
        when(db.update(contains("INSERT INTO submissions"),eq(12L),eq(7L),eq("PYTHON"),eq("print(1)"),eq(3L))).thenReturn(0);

        var service=new SubmissionService(db,problems,contests);
        var error=assertThrows(ResponseStatusException.class,()->service.submitContest(3,1001,"print(1)",Language.PYTHON,details));
        assertEquals(403,error.getStatusCode().value());
        verify(contests).requireSubmission(3,7,details);
        verify(db).update(argThat(sql->sql.contains("id,UTC_TIMESTAMP(6) FROM group_contests") &&
                sql.contains("starts_at<=UTC_TIMESTAMP(6)") && sql.contains("ends_at>UTC_TIMESTAMP(6)")),
            eq(12L),eq(7L),eq("PYTHON"),eq("print(1)"),eq(3L));
        verifyNoInteractions(problems);
        verify(db,never()).queryForObject(eq("SELECT LAST_INSERT_ID()"),eq(Long.class));
    }
}
