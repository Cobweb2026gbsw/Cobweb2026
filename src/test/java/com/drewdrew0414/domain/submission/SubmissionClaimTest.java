package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.group.ContestService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 전역 DB 큐를 건드리지 않고 claim의 새 lease 값과 시도 횟수 증가를 확인합니다. */
class SubmissionClaimTest {
    @Test void claimIssuesANewTokenAndIncrementsTheAttempt() {
        var db=mock(JdbcTemplate.class);
        var problems=mock(ProblemService.class);
        var service=new SubmissionService(db,problems,mock(ContestService.class));
        var queued=new HashMap<String,Object>();
        queued.put("id",77L);
        queued.put("judge_attempts",1);
        when(db.queryForList(contains("FOR UPDATE SKIP LOCKED"))).thenReturn(List.of(queued));

        var claim=service.claim();

        assertNotNull(claim);
        assertEquals(2,claim.get("judge_attempts"));
        String token=(String)claim.get("judge_token");
        assertNotNull(token);
        assertEquals(36,token.length());
        verify(db).update(argThat(sql -> sql.contains("judge_heartbeat_at=(clock_timestamp() AT TIME ZONE 'UTC')")
            && sql.contains("judge_attempts=judge_attempts+1")),eq(token),eq(77L));
    }
}
