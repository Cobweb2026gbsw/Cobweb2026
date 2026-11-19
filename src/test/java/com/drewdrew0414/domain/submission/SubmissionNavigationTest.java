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

class SubmissionNavigationTest {
    private CustomUserDetails user(long id) {
        var user=User.builder().username("reader").password("unused").build();
        ReflectionTestUtils.setField(user,"id",id);
        return new CustomUserDetails(user);
    }
    private Map<String,Object> row() {
        return new HashMap<>(Map.of("id",42L,"user_id",12L,"status","WRONG_ANSWER","problemNumber",1000,"groupId",7L));
    }
    @Test void ownSubmissionIncludesProblemDestinationWithoutRevealingSourceOrTestInputs() {
        var db=mock(JdbcTemplate.class);var contests=mock(ContestService.class);
        when(db.queryForList(contains("FROM submissions s JOIN problems"),eq(42L))).thenReturn(List.of(row()));
        when(db.queryForList(contains("FROM submission_test_case_results"),eq(42L))).thenReturn(List.of(Map.of("status","WRONG_ANSWER","execution_time_ms",2)));
        var result=new SubmissionService(db,mock(ProblemService.class),contests).detail(42,user(12));
        assertEquals(1000,result.get("problemNumber"));assertEquals(7L,result.get("groupId"));
        assertFalse(result.containsKey("sourceCode"));assertFalse(result.containsKey("diagnostic_json"));
        verifyNoInteractions(contests);
    }
    @Test void anotherUserCannotReadTheDestinationOrCasesOfOrdinarySubmission() {
        var db=mock(JdbcTemplate.class);
        when(db.queryForList(contains("FROM submissions s JOIN problems"),eq(42L))).thenReturn(List.of(row()));
        var error=assertThrows(ResponseStatusException.class,()->new SubmissionService(db,mock(ProblemService.class),mock(ContestService.class)).detail(42,user(99)));
        assertEquals(404,error.getStatusCode().value());
        verify(db,never()).queryForList(contains("submission_test_case_results"),anyLong());
    }
    @Test void contestDestinationRetainsExistingReplayAuthorization() {
        var db=mock(JdbcTemplate.class);var contests=mock(ContestService.class);var details=user(12);
        var submission=row();submission.put("contest_id",3L);submission.put("contestGroupId",7L);
        when(db.queryForList(contains("FROM submissions s JOIN problems"),eq(42L))).thenReturn(List.of(submission));
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(contests).requireSubmissionRead(3L,12L,details);
        assertThrows(ResponseStatusException.class,()->new SubmissionService(db,mock(ProblemService.class),contests).detail(42,details));
        verify(db,never()).queryForObject(contains("source_code"),eq(String.class),anyLong());
        verify(db,never()).queryForList(contains("submission_test_case_results"),anyLong());
    }
}
