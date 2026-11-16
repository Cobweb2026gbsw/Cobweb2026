package com.drewdrew0414.domain.submission;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 외부 프로세스 없이 소스 동일성·컨텍스트·결과 완전성 및 캐시 worker 분기를 확인합니다. */
class SubmissionReuseTest {
    private static final String IMAGE="sha256:"+"a".repeat(64), ENGINE="b".repeat(64);

    @Test void sourceLimitCountsUtf8BytesAndNeverNormalizesTheOriginal() {
        assertTrue(SubmissionService.validSource("#"+"x".repeat(65535)));
        assertFalse(SubmissionService.validSource("#"+"x".repeat(65536)));
        assertTrue(SubmissionService.validSource("가".repeat(21845)+"x"));
        assertFalse(SubmissionService.validSource("가".repeat(21846)));
        for(String invalid:List.of(""," \t\r\n","\ud800","\udc00")) assertFalse(SubmissionService.validSource(invalid));
        assertFalse(SubmissionService.validSource(null));
        for(String other:List.of("print(1)\n","PRINT(1)","print(1) ","print(1)\r\n"))
            assertNotEquals(SubmissionService.sourceDigest("print(1)"),SubmissionService.sourceDigest(other));
        assertNotEquals(SubmissionService.sourceDigest("é"),SubmissionService.sourceDigest("e\u0301"));
        assertEquals(65536,("가".repeat(21845)+"x").getBytes(StandardCharsets.UTF_8).length);
    }

    @Test void everyJudgingInputAndImmutableEngineIdentityChangesTheContext() {
        var problem=problem();var tests=List.of(test(11L,"1","2",1),test(12L,"3","4",2));
        String baseline=SubmissionService.contextDigest(problem,tests,IMAGE,ENGINE);
        assertNotNull(baseline);
        assertEquals(baseline,SubmissionService.contextDigest(problem,tests,IMAGE,ENGINE));
        for(var field:Map.of("execution_mode","FUNCTION","time_limit_ms",2000,"memory_limit_mb",512,"function_abi_compatible",true).entrySet()) {
            var changed=new HashMap<>(problem);changed.put(field.getKey(),field.getValue());
            assertNotEquals(baseline,SubmissionService.contextDigest(changed,tests,IMAGE,ENGINE));
        }
        assertNotEquals(baseline,SubmissionService.contextDigest(problem,tests,"sha256:"+"c".repeat(64),ENGINE));
        assertNotEquals(baseline,SubmissionService.contextDigest(problem,tests,IMAGE,"d".repeat(64)));
        assertNotEquals(baseline,SubmissionService.contextDigest(problem,List.of(tests.get(1),tests.get(0)),IMAGE,ENGINE));
        for(String field:List.of("id","input_data","expected_output","compare_type","display_order")) {
            var changed=new HashMap<>(tests.getFirst());changed.put(field,"changed");
            assertNotEquals(baseline,SubmissionService.contextDigest(problem,List.of(changed,tests.get(1)),IMAGE,ENGINE));
        }
        assertNull(SubmissionService.contextDigest(problem,tests,"cobweb-languages:local",ENGINE));
        assertNull(SubmissionService.contextDigest(problem,tests,IMAGE,null));
    }

    @Test void invalidOptionalCacheMetadataMissesButUnexpectedFailuresAreNotHidden() {
        assertNull(SubmissionService.contextDigest(Map.of(),List.of(),IMAGE,ENGINE));
        assertNull(SubmissionService.contextDigest(problem(),null,IMAGE,ENGINE));
        var broken=problem();broken.put("execution_mode",new Object(){
            @Override public String toString(){throw new IllegalStateException("unexpected internal failure");}
        });
        assertThrows(IllegalStateException.class,()->SubmissionService.contextDigest(broken,List.of(),IMAGE,ENGINE));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",SubmissionService.sourceDigest("abc"));
    }
    @Test void streamingContextHashRetainsTheLegacyByteFormatAndHandlesNearThirtyTwoMiB() throws Exception {
        var p=problem();var cases=List.of(test(11L,"a\u0000가\n","2",1),test(12L,"é","4",2));
        var bytes=new java.io.ByteArrayOutputStream();var output=new java.io.DataOutputStream(bytes);
        var values=new ArrayList<Object>(List.of("COBWEB_JUDGEMENT_V1",IMAGE,ENGINE,p.get("execution_mode"),p.get("time_limit_ms"),p.get("memory_limit_mb"),false));
        values.add(null);
        for(Object value:values){if(value==null)output.writeInt(-1);else{byte[] encoded=value.toString().getBytes(StandardCharsets.UTF_8);output.writeInt(encoded.length);output.write(encoded);}}
        output.writeInt(cases.size());
        for(var row:cases)for(String key:List.of("id","input_data","expected_output","compare_type","display_order")){
            byte[] encoded=row.get(key).toString().getBytes(StandardCharsets.UTF_8);output.writeInt(encoded.length);output.write(encoded);
        }
        output.flush();
        String old=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        assertEquals(old,SubmissionService.contextDigest(p,cases,IMAGE,ENGINE));
        String input="{\"args\":[["+"1234567890,".repeat(249999)+"1234567890]]}";
        var large=java.util.stream.IntStream.range(0,12).mapToObj(i->test(100L+i,input,"true",i+1)).toList();
        p.put("execution_mode","FUNCTION");p.put("function_abi_compatible",true);
        assertNotNull(SubmissionService.contextDigest(p,large,IMAGE,ENGINE));
    }
    @Test void genericSignatureChangesCannotReuseAnEarlierFunctionJudgement() {
        var problem=problem();problem.put("execution_mode","FUNCTION");problem.put("function_abi_compatible",true);
        var tests=List.of(test(11L,"{\"args\":[1]}","1",1));
        String legacy=SubmissionService.contextDigest(problem,tests,IMAGE,ENGINE);
        problem.put("function_signature","{\"name\":\"sol\",\"parameters\":[{\"name\":\"value\",\"type\":\"int\"}],\"returnType\":\"int\"}");
        String integer=SubmissionService.contextDigest(problem,tests,IMAGE,ENGINE);
        assertNotEquals(legacy,integer);
        problem.put("function_signature","{\"name\":\"solution\",\"parameters\":[{\"name\":\"value\",\"type\":\"int\"}],\"returnType\":\"int\"}");
        assertNotEquals(integer,SubmissionService.contextDigest(problem,tests,IMAGE,ENGINE));
    }
    @Test void workerCarriesTheStoredGenericSignatureIntoThePinnedPreparation() {
        var submissions=mock(SubmissionService.class);var runner=mock(DockerJudge.class);
        var prepared=mock(DockerJudge.Prepared.class);var claim=claim();var problem=problem();
        String signature="{\"name\":\"sol\",\"parameters\":[],\"returnType\":\"int\"}";
        problem.put("execution_mode","FUNCTION");problem.put("function_signature",signature);
        when(submissions.claim()).thenReturn(claim);when(submissions.heartbeat(claim)).thenReturn(true);
        when(runner.imageIdentity()).thenReturn(IMAGE);when(submissions.problem(3L)).thenReturn(problem);
        when(submissions.cases(3L)).thenReturn(List.of(test(11L,"{\"args\":[]}","1",1)));
        when(runner.preparePinned(Language.PYTHON,"print(1)","FUNCTION",IMAGE,signature)).thenReturn(prepared);
        when(prepared.failure()).thenReturn("COMPILATION_ERROR");
        new JudgeWorker(submissions,runner,60000).tick();
        verify(runner).preparePinned(Language.PYTHON,"print(1)","FUNCTION",IMAGE,signature);
        verify(prepared).close();verify(submissions).finish(anyMap(),anyList());
    }

    @Test void partialCorruptOrSystemErrorResultsCannotBeReused() {
        var tests=List.of(test(11L,"1","2",1),test(12L,"3","4",2));
        var original=original("ACCEPTED",100,7);
        var results=List.of(result(11L,"ACCEPTED",3),result(12L,"ACCEPTED",7));
        assertEquals(2,SubmissionService.reusableResults(original,results,tests).size());
        assertNull(SubmissionService.reusableResults(original,results.subList(0,1),tests));
        assertNull(SubmissionService.reusableResults(original,List.of(results.get(1),results.get(0)),tests));
        assertNull(SubmissionService.reusableResults(original,List.of(result(11L,"SYSTEM_ERROR",3),results.get(1)),tests));
        assertNull(SubmissionService.reusableResults(original,List.of(result(11L,"ACCEPTED",-1),results.get(1)),tests));
        assertNull(SubmissionService.reusableResults(original("ACCEPTED",0,7),results,tests));
        assertNull(SubmissionService.reusableResults(original("ACCEPTED",100,8),results,tests));
        for(String state:List.of("QUEUED","RUNNING","SYSTEM_ERROR"))
            assertNull(SubmissionService.reusableResults(original(state,0,7),results,tests));
        assertEquals(1,SubmissionService.reusableResults(original("COMPILATION_ERROR",0,0),
            List.of(result(null,"COMPILATION_ERROR",0)),tests).size());
    }

    @Test void actualJudgeClassesHaveAStableNonemptyFingerprint() {
        String value=DockerJudge.engineFingerprint();
        assertNotNull(value);assertTrue(value.matches("[0-9a-f]{64}"));
        assertEquals(value,DockerJudge.engineFingerprint());
    }

    @Test void workerCacheHitDoesNotCompileOrRunASecondProgram() {
        var submissions=mock(SubmissionService.class);var runner=mock(DockerJudge.class);
        var claim=claim();
        when(submissions.claim()).thenReturn(claim);
        when(submissions.heartbeat(claim)).thenReturn(true);
        when(runner.imageIdentity()).thenReturn(IMAGE);
        when(submissions.tryReuse(eq(claim),eq(IMAGE),anyString())).thenReturn(true);
        new JudgeWorker(submissions,runner,60000).tick();
        verify(submissions).tryReuse(eq(claim),eq(IMAGE),anyString());
        verify(runner,never()).prepare(any(),anyString(),anyString());
        verify(runner,never()).preparePinned(any(),anyString(),anyString(),anyString());
        verify(submissions,never()).finish(anyMap(),anyList());
    }

    @Test void failedReuseCannotLabelTheFallbackExecutionAsReused() {
        var submissions=mock(SubmissionService.class);var runner=mock(DockerJudge.class);
        var prepared=mock(DockerJudge.Prepared.class);var claim=claim();
        when(submissions.claim()).thenReturn(claim);
        when(submissions.heartbeat(claim)).thenReturn(true);
        when(runner.imageIdentity()).thenReturn(IMAGE);
        when(submissions.tryReuse(eq(claim),eq(IMAGE),anyString())).thenAnswer(invocation->{
            Map<String,Object> value=invocation.getArgument(0);
            value.put("reused_from_submission_id",99L);value.put("judge_context","stale");
            value.put("judge_image_id","stale");value.put("judge_engine","stale");
            throw new IllegalStateException("rolled back reuse");
        });
        when(submissions.problem(3L)).thenReturn(problem());
        when(submissions.cases(3L)).thenReturn(List.of(test(11L,"1","2",1)));
        when(runner.preparePinned(Language.PYTHON,"print(1)","STDIN",IMAGE)).thenReturn(prepared);
        when(prepared.failure()).thenReturn("COMPILATION_ERROR");
        new JudgeWorker(submissions,runner,60000).tick();
        verify(submissions).finish(argThat(value->value.get("reused_from_submission_id")==null &&
            IMAGE.equals(value.get("judge_image_id")) && !"stale".equals(value.get("judge_context")) &&
            DockerJudge.engineFingerprint().equals(value.get("judge_engine"))),anyList());
        verify(prepared).close();
    }

    @Test void reuseFinishFailureDoesNotMutateTheOriginalClaim() {
        var db=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var service=spy(new SubmissionService(db,mock(com.drewdrew0414.domain.problem.ProblemService.class),
            mock(com.drewdrew0414.domain.group.ContestService.class)));
        var claim=claim();
        when(db.queryForList(contains("SELECT id,status FROM users"),eq(2L))).thenReturn(List.of(Map.of("id",2L,"status","ACTIVE")));
        var current=new HashMap<>(claim);current.put("status","RUNNING");
        when(db.queryForList(eq("SELECT * FROM submissions WHERE id=? FOR UPDATE"),eq(1L))).thenReturn(List.of(current));
        var original=new HashMap<>(original("ACCEPTED",100,7));original.put("id",0L);original.put("source_code","print(1)");
        when(db.queryForList(contains("SELECT id,source_code,status,score,execution_time_ms"),
            eq(2L),eq(3L),eq("PYTHON"),isNull(),eq(1L),anyString(),anyString(),eq("print(1)"))).thenReturn(List.of(original));
        when(db.queryForList(contains("SELECT test_case_id,status,execution_time_ms"),eq(0L)))
            .thenReturn(List.of(result(11L,"ACCEPTED",7)));
        doReturn(problem()).when(service).problem(3L);
        doReturn(List.of(test(11L,"1","2",1))).when(service).cases(3L);
        doAnswer(invocation->{
            Map<String,Object> copied=invocation.getArgument(0);
            assertNotSame(claim,copied);assertEquals(0L,copied.get("reused_from_submission_id"));
            throw new org.springframework.dao.DataIntegrityViolationException("simulated transaction rollback");
        }).when(service).finish(anyMap(),anyList());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->service.tryReuse(claim,IMAGE,ENGINE));
        assertFalse(claim.containsKey("reused_from_submission_id"));
        assertFalse(claim.containsKey("judge_context"));
        assertFalse(claim.containsKey("judge_image_id"));
        assertFalse(claim.containsKey("judge_engine"));
    }

    private static Map<String,Object> problem() {
        return new HashMap<>(Map.of("execution_mode","STDIN","time_limit_ms",1000,"memory_limit_mb",256,"function_abi_compatible",false));
    }
    private static Map<String,Object> test(long id,String input,String expected,int order) {
        return new HashMap<>(Map.of("id",id,"input_data",input,"expected_output",expected,"compare_type","EXACT","display_order",order));
    }
    private static Map<String,Object> original(String status,int score,int elapsed) {
        return Map.of("status",status,"score",score,"execution_time_ms",elapsed);
    }
    private static Map<String,Object> result(Long id,String status,int elapsed) {
        var row=new HashMap<String,Object>();row.put("test_case_id",id);row.put("status",status);row.put("execution_time_ms",elapsed);return row;
    }
    private static Map<String,Object> claim() {
        return new HashMap<>(Map.of("id",1L,"user_id",2L,"problem_id",3L,"language","PYTHON","source_code","print(1)","judge_token","token"));
    }
}
