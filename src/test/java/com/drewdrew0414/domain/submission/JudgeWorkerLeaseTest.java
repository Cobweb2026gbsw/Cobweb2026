package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Docker를 실행하지 않고 블로킹 채점과 lease 갱신의 동시성 및 fencing을 검증합니다. */
class JudgeWorkerLeaseTest {
    @Test void periodicHeartbeatRunsWhilePrepareBlocksTheScheduledTick() throws Exception {
        var submissions=mock(SubmissionService.class);
        var runner=mock(DockerJudge.class);
        var prepared=mock(DockerJudge.Prepared.class);
        var claim=claim();
        var periodic=new CountDownLatch(1);
        when(submissions.claim()).thenReturn(claim);
        when(submissions.problem(2L)).thenReturn(problem());
        when(submissions.heartbeat(claim)).thenAnswer(invocation -> {
            if(Thread.currentThread().getName().startsWith("judge-lease-")) periodic.countDown();
            return true;
        });
        when(runner.prepare(Language.PYTHON,"print(1)","STDIN")).thenAnswer(invocation -> {
            assertTrue(periodic.await(2,TimeUnit.SECONDS),"prepare가 막힌 동안 별도 heartbeat가 실행돼야 합니다.");
            return prepared;
        });
        when(prepared.failure()).thenReturn("COMPILATION_ERROR");

        new JudgeWorker(submissions,runner,10).tick();

        assertEquals(0,periodic.getCount());
        verify(submissions,times(1)).finish(eq(claim),argThat(results ->
            results.size()==1 && "COMPILATION_ERROR".equals(results.getFirst().status())));
        verify(prepared).close();
    }

    @Test void aLostTokenDuringAUserCaseStopsLaterCasesAndFinish() throws Exception {
        var submissions=mock(SubmissionService.class);
        var runner=mock(DockerJudge.class);
        var prepared=mock(DockerJudge.Prepared.class);
        var claim=claim();
        var running=new CountDownLatch(1);
        var leaseLost=new CountDownLatch(1);
        var calls=new AtomicInteger();
        when(submissions.claim()).thenReturn(claim);
        when(submissions.problem(2L)).thenReturn(problem());
        when(submissions.cases(2L)).thenReturn(List.of(testCase(101L),testCase(102L)));
        when(submissions.heartbeat(claim)).thenAnswer(invocation -> {
            calls.incrementAndGet();
            if(Thread.currentThread().getName().startsWith("judge-lease-") && running.getCount()==0) {
                leaseLost.countDown();
                return false;
            }
            return true;
        });
        when(runner.prepare(Language.PYTHON,"print(1)","STDIN")).thenReturn(prepared);
        when(prepared.run(eq("in"),eq("out"),eq(1000),eq(128),anyLong())).thenAnswer(invocation -> {
            running.countDown();
            assertTrue(leaseLost.await(2,TimeUnit.SECONDS),"채점 도중 heartbeat가 실행돼야 합니다.");
            return new SubmissionService.JudgeResult(invocation.getArgument(4),"ACCEPTED",1);
        });

        new JudgeWorker(submissions,runner,10).tick();

        assertEquals(0,leaseLost.getCount());
        assertTrue(calls.get()>=2);
        verify(prepared,times(1)).run(eq("in"),eq("out"),eq(1000),eq(128),anyLong());
        verify(submissions,never()).finish(anyMap(),anyList());
        verify(prepared).close();
    }

    @Test void threeConsecutiveHeartbeatErrorsInvalidateTheLease() {
        var submissions=mock(SubmissionService.class);
        var claim=claim();
        when(submissions.heartbeat(claim)).thenThrow(new IllegalStateException("private source must not be logged"));

        try(var lease=new JudgeLease(submissions,claim,60000)) {
            assertTrue(lease.renew("first"));
            assertTrue(lease.renew("second"));
            assertFalse(lease.renew("third"));
            assertFalse(lease.current());
        }
        verify(submissions,times(3)).heartbeat(claim);
    }

    private static Map<String,Object> claim() {
        var claim=new HashMap<String,Object>();
        claim.put("id",1L);
        claim.put("user_id",3L);
        claim.put("problem_id",2L);
        claim.put("judge_token","secret-token");
        claim.put("language","PYTHON");
        claim.put("source_code","print(1)");
        return claim;
    }
    private static Map<String,Object> problem() {
        return Map.of("execution_mode","STDIN","time_limit_ms",1000,"memory_limit_mb",128);
    }
    private static Map<String,Object> testCase(long id) {
        return Map.of("id",id,"input_data","in","expected_output","out");
    }
}
