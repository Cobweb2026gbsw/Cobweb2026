package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 사용자 입력 전용 워커의 격리·lease 경계입니다. 외부 프로세스/DB를 실행하지 않습니다. */
class TestRunWorkerTest {
    private static final String IMAGE="sha256:"+"b".repeat(64);
    @AfterEach void clearTestInterrupt(){Thread.interrupted();}

    @Test void runsOnlyUserCasesThroughPinnedDockerPreparationAndDoesNotDependOnSubmissionService() {
        var fixture=fixture();fixture.worker().tick();
        verify(fixture.runner()).preparePinned(Language.PYTHON,"print(input())","STDIN",IMAGE,null);
        verify(fixture.prepared()).runForTesting("user-input-1","user-expected-1",2000,256);
        verify(fixture.prepared()).runForTesting("user-input-2","user-expected-2",2000,256);
        verify(fixture.prepared()).close();
        var rows=finished(fixture,false);
        assertEquals(2,rows.size());
        assertEquals(new TestRunService.Result("user-input-1","user-expected-1","user-expected-1","ACCEPTED",7,null),rows.getFirst());
        assertEquals("user-input-2",rows.getLast().input());
        assertTrue(Arrays.stream(TestRunWorker.class.getDeclaredFields()).noneMatch(field->field.getType()==SubmissionService.class));
        verify(fixture.runner()).imageIdentity();verifyNoMoreInteractions(fixture.runner());
    }
    @Test void carriesTheStoredPublicFunctionSignatureWithoutReadingPrivateTestTables() {
        var fixture=fixture();String signature="{\"name\":\"sol\",\"parameters\":[],\"returnType\":\"int\"}";
        fixture.run().put("execution_mode","FUNCTION");fixture.run().put("function_signature",signature);
        when(fixture.runner().preparePinned(Language.PYTHON,"print(input())","FUNCTION",IMAGE,signature)).thenReturn(fixture.prepared());
        fixture.worker().tick();
        verify(fixture.runner()).preparePinned(Language.PYTHON,"print(input())","FUNCTION",IMAGE,signature);
        verify(fixture.tests()).cases(fixture.run());
        assertEquals(2,finished(fixture,false).size());verify(fixture.prepared()).close();
    }
    @Test void noQueuedClaimDoesNotTouchDockerOrFinishAResult() {
        var fixture=fixture();when(fixture.tests().claim()).thenReturn(null);
        fixture.worker().tick();verifyNoInteractions(fixture.runner(),fixture.prepared());
        verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void claimFailureIsBoundedAndDoesNotTriggerHostOrDockerFallback() {
        var fixture=fixture();when(fixture.tests().claim()).thenThrow(new IllegalStateException("private-database-detail"));
        assertDoesNotThrow(fixture.worker()::tick);verifyNoInteractions(fixture.runner(),fixture.prepared());
        verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostBeforeImageLookupDoesNotPrepareRunOrFinish() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(false);
        fixture.worker().tick();verifyNoInteractions(fixture.runner(),fixture.prepared());
        verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostDuringImageLookupDoesNotStartPreparation() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(true,false);
        fixture.worker().tick();verify(fixture.runner()).imageIdentity();verifyNoMoreInteractions(fixture.runner());
        verifyNoInteractions(fixture.prepared());verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostAfterPreparationClosesItsResourcesWithoutRunningCases() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(true,true,false);
        fixture.worker().tick();verify(fixture.prepared()).close();
        verify(fixture.prepared(),never()).runForTesting(anyString(),anyString(),anyInt(),anyInt());
        verify(fixture.tests(),never()).cases(anyMap());verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostBeforeFirstCaseClosesWithoutRunningOrFinishing() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(true,true,true,false);
        fixture.worker().tick();verify(fixture.prepared()).close();
        verify(fixture.prepared(),never()).runForTesting(anyString(),anyString(),anyInt(),anyInt());
        verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostBetweenCasesStopsBeforeTheNextRunAndDoesNotFinishPartialData() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(true,true,true,true,false);
        fixture.worker().tick();verify(fixture.prepared()).runForTesting("user-input-1","user-expected-1",2000,256);
        verify(fixture.prepared(),never()).runForTesting(eq("user-input-2"),anyString(),anyInt(),anyInt());
        verify(fixture.prepared()).close();verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void leaseLostAfterLastCaseDoesNotAttemptToFinishAStaleResult() {
        var fixture=fixture();when(fixture.tests().current(fixture.run())).thenReturn(true,true,true,true,true,false);
        fixture.worker().tick();verify(fixture.prepared(),times(2)).runForTesting(anyString(),anyString(),anyInt(),anyInt());
        verify(fixture.prepared()).close();verify(fixture.tests(),never()).finish(anyMap(),anyList(),anyBoolean());
    }
    @Test void unavailableImageFinishesSafeSystemErrorWithoutAnyPreparationOrHostFallback() {
        var fixture=fixture();when(fixture.runner().imageIdentity()).thenReturn(null);
        fixture.worker().tick();assertTrue(finished(fixture,true).isEmpty());
        verify(fixture.runner()).imageIdentity();verifyNoMoreInteractions(fixture.runner());verifyNoInteractions(fixture.prepared());
    }
    @Test void prepareAndRunFailuresNeverBecomeActualOutputAndAlwaysCloseCreatedResources() {
        var preparation=fixture();when(preparation.runner().preparePinned(Language.PYTHON,"print(input())","STDIN",IMAGE,null))
            .thenThrow(new IllegalStateException("private-host-prepare-error"));
        assertDoesNotThrow(preparation.worker()::tick);assertTrue(finished(preparation,true).isEmpty());
        var execution=fixture();when(execution.prepared().runForTesting(anyString(),anyString(),anyInt(),anyInt()))
            .thenThrow(new IllegalStateException("private-host-run-error"));
        assertDoesNotThrow(execution.worker()::tick);assertTrue(finished(execution,true).isEmpty());verify(execution.prepared()).close();
    }
    @Test void systemErrorSuppressesActualAndDiagnosticEvenIfTheDockerAdapterReturnsThem() {
        var fixture=fixture();var fakeDiagnostic=JudgeDiagnostic.runtime(Language.PYTHON,"pass","ValueError: private-host-data",false);
        when(fixture.prepared().runForTesting(anyString(),anyString(),anyInt(),anyInt())).thenReturn(
            new DockerJudge.Prepared.TestOutput(new SubmissionService.JudgeResult(null,"SYSTEM_ERROR",0,fakeDiagnostic),"private-host-output"));
        fixture.worker().tick();var rows=finished(fixture,true);assertEquals(1,rows.size());
        assertEquals("SYSTEM_ERROR",rows.getFirst().status());assertNull(rows.getFirst().actual());assertNull(rows.getFirst().diagnostic());
        verify(fixture.prepared(),times(1)).runForTesting(anyString(),anyString(),anyInt(),anyInt());verify(fixture.prepared()).close();
    }
    @Test void ordinaryRuntimeFailureKeepsOnlyUserOutputAndSafeDiagnosticAndMayContinueOtherCases() {
        var fixture=fixture();var diagnostic=JudgeDiagnostic.runtime(Language.PYTHON,"value=input()\nraise ValueError(value)","ValueError: hidden",false);
        when(fixture.prepared().runForTesting(eq("user-input-1"),anyString(),anyInt(),anyInt())).thenReturn(
            new DockerJudge.Prepared.TestOutput(new SubmissionService.JudgeResult(null,"RUNTIME_ERROR",9,diagnostic),"user-output-before-error"));
        fixture.worker().tick();var rows=finished(fixture,false);assertEquals(2,rows.size());
        assertEquals("RUNTIME_ERROR",rows.getFirst().status());assertEquals(diagnostic.publicValue(),rows.getFirst().diagnostic());
        assertEquals("user-output-before-error",rows.getFirst().actual());verify(fixture.prepared()).close();
    }
    @Test void interruptionBeforeClaimOrDuringImageLookupDoesNotPrepareOrFinish() {
        var before=fixture();Thread.currentThread().interrupt();before.worker().tick();Thread.interrupted();
        verifyNoInteractions(before.tests(),before.runner(),before.prepared());
        var lookup=fixture();when(lookup.runner().imageIdentity()).thenAnswer(call->{Thread.currentThread().interrupt();return IMAGE;});
        lookup.worker().tick();Thread.interrupted();verify(lookup.runner()).imageIdentity();verifyNoMoreInteractions(lookup.runner());
        verify(lookup.tests(),never()).finish(anyMap(),anyList(),anyBoolean());verifyNoInteractions(lookup.prepared());
    }
    @Test void completionFailureIsContainedAfterDockerResourcesHaveAlreadyClosed() {
        var fixture=fixture();doThrow(new IllegalStateException("private-db-finish")).when(fixture.tests()).finish(anyMap(),anyList(),anyBoolean());
        assertDoesNotThrow(fixture.worker()::tick);verify(fixture.prepared()).close();
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private List<TestRunService.Result> finished(Fixture fixture,boolean failed) {
        ArgumentCaptor<List> results=ArgumentCaptor.forClass(List.class);
        verify(fixture.tests()).finish(eq(fixture.run()),results.capture(),eq(failed));
        return results.getValue();
    }
    private Fixture fixture() {
        var tests=mock(TestRunService.class);var runner=mock(DockerJudge.class);var prepared=mock(DockerJudge.Prepared.class);
        var run=new HashMap<String,Object>();run.put("id",41L);run.put("user_id",17L);run.put("problem_id",23L);
        run.put("language","PYTHON");run.put("source_code","print(input())");run.put("execution_mode","STDIN");
        run.put("function_signature",null);run.put("judge_token","test-run-lease");run.put("time_limit_ms",2000);run.put("memory_limit_mb",256);
        when(tests.claim()).thenReturn(run);when(tests.current(run)).thenReturn(true);when(runner.imageIdentity()).thenReturn(IMAGE);
        when(runner.preparePinned(Language.PYTHON,"print(input())","STDIN",IMAGE,null)).thenReturn(prepared);
        when(tests.cases(run)).thenReturn(List.of(new TestRunService.Case("user-input-1","user-expected-1"),new TestRunService.Case("user-input-2","user-expected-2")));
        when(prepared.runForTesting(anyString(),anyString(),anyInt(),anyInt())).thenAnswer(call->new DockerJudge.Prepared.TestOutput(
            new SubmissionService.JudgeResult(null,"ACCEPTED",7),call.getArgument(1)));
        return new Fixture(tests,runner,prepared,new TestRunWorker(tests,runner),run);
    }
    private record Fixture(TestRunService tests,DockerJudge runner,DockerJudge.Prepared prepared,TestRunWorker worker,Map<String,Object> run) {}
}
