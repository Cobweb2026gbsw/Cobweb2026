package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContestServiceTest {
    @Test void linkedQuestionIsHiddenFromTheOrdinaryProblemApiBeforeTheContest() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        when(db.queryForList(contains("FROM contest_problems cp"),eq(13L)))
            .thenReturn(List.of(Map.of("group_id",9L,"author_id",99L)));

        var error=assertThrows(ResponseStatusException.class,
            () -> contests.requireQuestionRead(13,user(42)));
        assertEquals(HttpStatus.NOT_FOUND,error.getStatusCode());
        verify(groups,atLeastOnce()).can(eq(9L),any(),any());
    }

    @Test void authorMayReadTheLinkedQuestionThroughTheOrdinaryProblemApi() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        when(db.queryForList(contains("FROM contest_problems cp"),eq(13L)))
            .thenReturn(List.of(Map.of("group_id",9L,"author_id",42L)));

        assertDoesNotThrow(() -> contests.requireQuestionRead(13,user(42)));
        verifyNoInteractions(groups);
    }

    @Test void submittingOutsideTheServerUtcWindowIsRejectedEvenWithTakePermission() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        when(db.queryForList(contains("FROM group_contests WHERE id=? FOR UPDATE"),eq(5L)))
            .thenReturn(List.of(contest()));
        when(db.queryForObject(eq("SELECT UTC_TIMESTAMP(6)"),eq(Timestamp.class)))
            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2030,1,1,9,59)));
        when(groups.can(eq(9L),any(),eq(GroupPermission.TAKE_CONTESTS))).thenReturn(true);

        var error=assertThrows(ResponseStatusException.class,
            () -> contests.requireSubmission(5,13,user(42)));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verify(db,never()).queryForObject(contains("COUNT(*) FROM contest_problems"),eq(Long.class),any(),any());
    }

    @Test void anUnlinkedProblemCannotBeSubmittedDuringTheContest() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        when(db.queryForList(contains("FROM group_contests WHERE id=? FOR UPDATE"),eq(5L)))
            .thenReturn(List.of(contest()));
        when(db.queryForObject(eq("SELECT UTC_TIMESTAMP(6)"),eq(Timestamp.class)))
            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2030,1,1,10,30)));
        when(groups.can(eq(9L),any(),eq(GroupPermission.TAKE_CONTESTS))).thenReturn(true);
        when(db.queryForObject(contains("COUNT(*) FROM contest_problems"),eq(Long.class),eq(5L),eq(13L)))
            .thenReturn(0L);

        var error=assertThrows(ResponseStatusException.class,
            () -> contests.requireSubmission(5,13,user(42)));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
    }

    @Test void endedContestDoesNotRevealOwnSourceWithoutReplayAndParticipation() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        when(db.queryForList(contains("FROM group_contests WHERE group_id=? AND id=?"),eq(9L),eq(5L)))
            .thenReturn(List.of(contest()));
        when(db.queryForObject(eq("SELECT UTC_TIMESTAMP(6)"),eq(Timestamp.class)))
            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2030,1,1,11,1)));
        when(groups.can(eq(9L),any(),eq(GroupPermission.TAKE_CONTESTS))).thenReturn(true);

        var error=assertThrows(ResponseStatusException.class,() -> contests.mine(9,5,user(42)));
        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verify(db,never()).queryForList(contains("s.source_code"),eq(5L),eq(42L));
    }

    @Test void creationLocksTheCreatorBeforeTheGroup() {
        var db=mock(JdbcTemplate.class);
        var contests=new ContestService(db,mock(GroupService.class));
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L))
            .thenReturn(List.of(Map.of("id",42L)));
        when(db.queryForList("SELECT id FROM study_groups WHERE id=? AND status='ACCEPTED' FOR UPDATE",9L))
            .thenReturn(List.of(Map.of("id",9L)));

        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> contests.create(9,null,user(42))).getStatusCode());

        var order=inOrder(db);
        order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
        order.verify(db).queryForList("SELECT id FROM study_groups WHERE id=? AND status='ACCEPTED' FOR UPDATE",9L);
    }

    @Test void monitoringEventLocksTheContestBeforeTheParticipant() {
        var db=mock(JdbcTemplate.class);
        var groups=mock(GroupService.class);
        var contests=new ContestService(db,groups);
        var participant=user(42);
        when(db.queryForList("SELECT * FROM group_contests WHERE group_id=? AND id=? FOR UPDATE",9L,5L))
            .thenReturn(List.of(contest()));
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L))
            .thenReturn(List.of(Map.of("id",42L)));
        when(db.queryForObject("SELECT UTC_TIMESTAMP(6)",Timestamp.class))
            .thenReturn(Timestamp.valueOf(LocalDateTime.of(2030,1,1,10,30)));
        when(groups.can(9L,participant,GroupPermission.TAKE_CONTESTS)).thenReturn(true);

        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> contests.event(9,5,null,participant)).getStatusCode());

        var order=inOrder(db);
        order.verify(db).queryForList("SELECT * FROM group_contests WHERE group_id=? AND id=? FOR UPDATE",9L,5L);
        order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
    }

    @Test void contestSubmissionAndJudgementTimesAreExplicitUtcStrings() {
        var db=mock(JdbcTemplate.class);
        var contests=new ContestService(db,mock(GroupService.class));
        when(db.queryForList(contains("FROM group_contests WHERE group_id=? AND id=?"),eq(9L),eq(5L)))
            .thenReturn(List.of(contest()));
        when(db.queryForList(contains("FROM submissions s JOIN problems p"),eq(5L),eq(42L)))
            .thenReturn(List.of(Map.of("id",71L,"problem_number",1001,"language","PYTHON",
                "status","ACCEPTED","score",100,"execution_time_ms",4,
                "submitted_at",Timestamp.valueOf(LocalDateTime.of(2030,1,1,10,0,0,123456000)),
                "judged_at",Timestamp.valueOf(LocalDateTime.of(2030,1,1,10,0,1,654321000)),
                "source_code","print(1)")));

        var result=contests.submissions(9,5,42,user(7));
        @SuppressWarnings("unchecked")
        var items=(List<Map<String,Object>>)result.get("submissions");

        assertEquals("2030-01-01T10:00:00.123456Z",items.getFirst().get("submittedAt"));
        assertEquals("2030-01-01T10:00:01.654321Z",items.getFirst().get("judgedAt"));
    }

    private static Map<String,Object> contest() {
        return Map.of("id",5L,"group_id",9L,
            "starts_at",Timestamp.valueOf(LocalDateTime.of(2030,1,1,10,0)),
            "ends_at",Timestamp.valueOf(LocalDateTime.of(2030,1,1,11,0)));
    }
    private static CustomUserDetails user(long id) {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(id);
        return user;
    }
}
