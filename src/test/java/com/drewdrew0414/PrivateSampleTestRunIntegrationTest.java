package com.drewdrew0414;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.submission.DockerJudge;
import com.drewdrew0414.domain.submission.TestRunService;
import com.drewdrew0414.domain.submission.TestRunWorker;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 격리 DB/HTTP에서 비공개 2케이스의 스냅샷·대용량·응답 비노출을 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"judge.enabled=false","curriculum.level0.enabled=false"})
@ActiveProfiles("test")
class PrivateSampleTestRunIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ProblemService problems;
    @Autowired TestRunService tests;
    @Autowired JwtTokenProvider jwt;
    @Autowired PlatformTransactionManager transactions;
    @Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> problemIds=new ArrayList<>();
    private final List<CustomUserDetails> accounts=new ArrayList<>();
    private CustomUserDetails author,solver,other;

    @BeforeEach void setup() {
        assertTrue(Set.of("cobweb_test","cobweb_feature_test","cobweb_revalidation_test","cobweb_expert_test")
            .contains(db.queryForObject("SELECT current_database()",String.class)),"A dedicated test database is required");
        author=account(Role.OPERATOR);solver=account(Role.USER);other=account(Role.USER);
    }
    @AfterEach void cleanupOwnFixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            if(author!=null)for(long id:problemIds)db.update("DELETE FROM problems WHERE id=? AND author_id=?",id,author.getUserId());
            for(var account:accounts)db.update("DELETE FROM users WHERE id=? AND username=?",account.getUserId(),account.getUsername());
        });
    }

    @Test void selectsExactlyTheFirstTwoActiveFormalCasesAndKeepsTheSnapshotServerOnly() throws Exception {
        int number=create("FUNCTION",signature("int","int"));long pid=problemId(number);
        db.update("INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,display_order,is_active) VALUES(?,?,'333','EXACT',3,TRUE)",pid,"{\"args\":[333]}");
        db.update("INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,display_order,is_active) VALUES(?,?,'444','EXACT',4,FALSE)",pid,"{\"args\":[444]}");
        var before=statistics(number);long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_test_run_private_cases WHERE run_id=?",Integer.class,id));
        var stored=db.queryForMap("SELECT private_sample,input_cases FROM user_test_runs WHERE id=?",id);
        assertTrue(TestRunService.isPrivateSample(stored));
        assertFalse(stored.get("input_cases").toString().contains("987654321"));
        assertTrue(stored.get("input_cases").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<512);
        var cases=tests.privateCases(run(id));assertEquals(List.of(
            new TestRunService.Case("{\"args\":[987654321]}","987654321"),new TestRunService.Case("{\"args\":[876543210]}","876543210")),cases);
        var detail=data(http("GET","/api/test-runs/"+id,null,solver));
        assertEquals(true,detail.get("privateSample").asBoolean());assertEquals(2,detail.get("testCount").asInt());
        assertEquals("QUEUED",detail.get("status").asText());assertEquals(0,detail.get("results").size());
        assertEquals(before,statistics(number));assertFalse(detail.toString().contains("987654321"));
        assertEquals(404,http("GET","/api/test-runs/"+id,null,other).statusCode());
        assertEquals(404,http("GET","/api/test-runs/"+id,null,author).statusCode());
    }

    @Test void rejectsCallerSelectedCasesTooFewFormalCasesAndDisallowedLanguagesBeforeQueueInsert() throws Exception {
        int number=create("FUNCTION",signature("int","int"));String source="def sol(value):\n    return value\n";
        for(var mixed:List.of(List.<TestRunService.Case>of(),List.of(new TestRunService.Case("{\"args\":[1]}","1"))))
            assertEquals(400,http("POST",endpoint(number),new TestRunService.Request("PYTHON",source,mixed,true),solver).statusCode());
        assertEquals(400,http("POST",endpoint(number),privateRequest("RUST",source),solver).statusCode());
        assertEquals(401,http("POST",endpoint(number),privateRequest("PYTHON",source),null).statusCode());
        db.update("UPDATE problem_test_cases SET is_active=FALSE WHERE problem_id=? AND display_order=2",problemId(number));
        assertEquals(409,http("POST",endpoint(number),privateRequest("PYTHON",source),solver).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
    }

    @Test void completedReadWhitelistsEvenOldOrPoisonedResultsAndPendingReadReturnsNothing() throws Exception {
        int number=create("FUNCTION",signature("int","int"));long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        String poisoned="[{\"status\":\"ACCEPTED\",\"timeMs\":7,\"input\":\"private-input\",\"expected\":\"private-expected\",\"actual\":\"private-actual\",\"diagnostic\":{\"message\":\"private-diagnostic\"},\"testCaseId\":99},{\"status\":\"private-status\",\"timeMs\":-5,\"sourceCode\":\"private-source\"}]";
        db.update("UPDATE user_test_runs SET status='COMPLETED',result_json=CAST(? AS jsonb) WHERE id=?",poisoned,id);
        var detail=data(http("GET","/api/test-runs/"+id,null,solver));assertPrivateShape(detail);
        assertEquals("ACCEPTED",detail.get("results").get(0).get("status").asText());
        assertEquals("SYSTEM_ERROR",detail.get("results").get(1).get("status").asText());assertEquals(0,detail.get("results").get(1).get("timeMs").asInt());
        assertFalse(detail.toString().contains("private-"));
        db.update("UPDATE user_test_runs SET status='RUNNING' WHERE id=?",id);
        assertEquals(0,data(http("GET","/api/test-runs/"+id,null,solver)).get("results").size());
        problems.setVisibility(number,new ProblemRequests.Visibility("PRIVATE","sample access revoked"),author);
        assertEquals(404,http("GET","/api/test-runs/"+id,null,solver).statusCode());
    }

    @Test void finishUsesDatabaseModeAndNeverPersistsRawFieldsEvenWhenRunMetadataIsWrong() throws Exception {
        int number=create("FUNCTION",signature("int","int"));long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        db.update("UPDATE user_test_runs SET status='RUNNING',judge_token='private-token',lease_until=((clock_timestamp() AT TIME ZONE 'UTC') + INTERVAL '1 minute') WHERE id=?",id);
        var claim=run(id);claim.put("private_sample",false);
        tests.finish(claim,List.of(new TestRunService.Result("private-input","private-expected","private-actual","WRONG_ANSWER",11,Map.of("message","private-diagnostic")),
            new TestRunService.Result("private-input-2","private-expected-2","private-actual-2","ACCEPTED",13,null)),false);
        String stored=db.queryForObject("SELECT result_json FROM user_test_runs WHERE id=?",String.class,id);
        assertFalse(stored.contains("private-"));assertFalse(stored.contains("input"));assertFalse(stored.contains("actual"));assertFalse(stored.contains("diagnostic"));
        var detail=data(http("GET","/api/test-runs/"+id,null,solver));assertPrivateShape(detail);
        assertEquals("COMPLETED",detail.get("status").asText());assertEquals(11,detail.get("results").get(0).get("timeMs").asInt());
    }

    @Test void onePrivateResultCannotCompleteAndIncompleteStoredCompletionsFailClosed() throws Exception {
        int number=create("FUNCTION",signature("int","int"));long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        db.update("UPDATE user_test_runs SET status='RUNNING',judge_token='partial-token',lease_until=((clock_timestamp() AT TIME ZONE 'UTC') + INTERVAL '1 minute') WHERE id=?",id);
        tests.finish(run(id),List.of(new TestRunService.Result("private-input","private-expected","private-actual","ACCEPTED",7,null)),false);
        assertEquals("SYSTEM_ERROR",db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,id));
        assertEquals(0,json.readTree(db.queryForObject("SELECT result_json FROM user_test_runs WHERE id=?",String.class,id)).size());
        var failed=data(http("GET","/api/test-runs/"+id,null,solver));assertEquals("SYSTEM_ERROR",failed.get("status").asText());assertEquals(0,failed.get("results").size());
        // 구버전/오염 저장값이 완료라고 주장하더라도 정확히 두 결과가 아니면 반환하지 않습니다.
        for(String malformed:java.util.Arrays.asList(null,"[]","[{\"status\":\"ACCEPTED\",\"timeMs\":7,\"actual\":\"private-actual\"}]","[{},{},{}]")) {
            db.update("UPDATE user_test_runs SET status='COMPLETED',result_json=CAST(? AS jsonb) WHERE id=?",malformed,id);
            assertThrows(IllegalStateException.class,()->tests.detail(id,solver));
            var response=http("GET","/api/test-runs/"+id,null,solver);
            assertEquals(500,response.statusCode());assertFalse(response.body().contains("private-actual"));
        }
    }

    @Test void snapshotSurvivesSourceCaseEditsAndMissingSnapshotDoesNotFallBack() {
        int number=create("FUNCTION",signature("int","int"));long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        db.update("UPDATE problem_test_cases SET input_data='{\"args\":[123]}',expected_output='456',is_active=FALSE WHERE problem_id=?",problemId(number));
        var cases=tests.privateCases(run(id));assertEquals("{\"args\":[987654321]}",cases.getFirst().input());assertEquals("876543210",cases.getLast().expected());
        db.update("DELETE FROM user_test_run_private_cases WHERE run_id=? AND display_order=2",id);
        assertThrows(IllegalStateException.class,()->tests.privateCases(run(id)));
    }

    @Test void privateSnapshotSupportsLargerThanManualEnvelopeWithoutChangingManualLimits() {
        int number=create("FUNCTION",signature("int[]","int"));String input="{\"args\":[["+"1234567890,".repeat(99999)+"1234567890]]}";
        db.update("UPDATE problem_test_cases SET input_data=?,expected_output='1' WHERE problem_id=?",input,problemId(number));
        String source="def sol(value):\n    return 1\n";long id=tests.create(number,privateRequest("PYTHON",source),solver);
        assertTrue(db.queryForObject("SELECT MIN(OCTET_LENGTH(input_data)) FROM user_test_run_private_cases WHERE run_id=?",Long.class,id)>524288);
        assertTrue(tests.privateCases(run(id)).stream().allMatch(row->input.equals(row.input())));
        var rejected=assertThrows(ApiException.class,()->tests.create(number,new TestRunService.Request("PYTHON",source,List.of(new TestRunService.Case(input,"1"))),solver));
        assertEquals(400,rejected.getStatusCode().value());
    }

    @Test void oversizePrivateCellsAndThirtyTwoMiBBundlesAreRejectedWithoutInsertingRuns() {
        int number=create("STDIN",null);long pid=problemId(number);String source="print(input())\n";
        String atLimit="x".repeat(16*1024*1024);
        db.update("UPDATE problem_test_cases SET input_data=?,expected_output='y' WHERE problem_id=?",atLimit,pid);
        assertEquals(409,assertThrows(ApiException.class,()->tests.create(number,privateRequest("PYTHON",source),solver)).getStatusCode().value());
        db.update("UPDATE problem_test_cases SET input_data=?,expected_output='' WHERE problem_id=?",atLimit+"x",pid);
        assertEquals(409,assertThrows(ApiException.class,()->tests.create(number,privateRequest("PYTHON",source),solver)).getStatusCode().value());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
    }

    @Test void privateRequestsRetainPendingLimitsCurrentAccountAndProblemAccessChecks() {
        int number=create("FUNCTION",signature("int","int"));var request=privateRequest("PYTHON","def sol(value):\n    return value\n");
        tests.create(number,request,solver);tests.create(number,request,solver);
        assertEquals(429,assertThrows(ApiException.class,()->tests.create(number,request,solver)).getStatusCode().value());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
        problems.setVisibility(number,new ProblemRequests.Visibility("PRIVATE","sample fixture private"),author);
        assertEquals(404,assertThrows(ApiException.class,()->tests.create(number,request,other)).getStatusCode().value());
        db.update("UPDATE users SET status='STOPPED' WHERE id=?",other.getUserId());
        assertEquals(403,assertThrows(ApiException.class,()->tests.create(number,request,other)).getStatusCode().value());
    }

    @Test void migrationKeepsOldRowsManualAndConstrainsPrivateBooleanAndSnapshotOrdinals() {
        int number=create("FUNCTION",signature("int","int"));long id=tests.create(number,new TestRunService.Request("PYTHON","def sol(value):\n    return value\n",List.of(new TestRunService.Case("{\"args\":[1]}","1"))),solver);
        assertFalse(TestRunService.isPrivateSample(run(id)));
        PostgresTestSupport.rejected(db,()->db.update("UPDATE user_test_runs SET private_sample=2 WHERE id=?",id));
        PostgresTestSupport.rejected(db,()->db.update("INSERT INTO user_test_run_private_cases(run_id,display_order,input_data,expected_output,compare_type) VALUES(?,3,'','', 'EXACT')",id));
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void dockerFunctionAndStdinEchoCannotRevealPrivateInputOrActualOrChangeFormalStatistics() throws Exception {
        var worker=worker();
        for(String mode:List.of("FUNCTION","STDIN")) {
            int number=create(mode,mode.equals("FUNCTION")?signature("int","int"):null);var before=statistics(number);
            String source=mode.equals("FUNCTION")?"def sol(value):\n    return value\n":"print(input())\n";
            long id=tests.create(number,privateRequest("PYTHON",source),solver);
            var detail=finish(worker,id);assertPrivateShape(detail);
            for(var result:detail.get("results"))assertEquals("ACCEPTED",result.get("status").asText());
            assertFalse(detail.toString().contains("987654321"));assertFalse(detail.toString().contains("PRIVATE_STDIN"));assertEquals(before,statistics(number));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void dockerCompileAndRuntimeFailuresExposeOnlyStatusAndTimeForBothSamples() throws Exception {
        var worker=worker();int number=create("FUNCTION",signature("int","int"));
        for(var body:List.of(privateRequest("C","int main( {\n"),privateRequest("PYTHON","def sol(value):\n    raise ValueError(value)\n"))) {
            long id=tests.create(number,body,solver);var detail=finish(worker,id);assertPrivateShape(detail);
            for(var result:detail.get("results"))assertEquals(body.language().equals("C")?"COMPILATION_ERROR":"RUNTIME_ERROR",result.get("status").asText());
            assertFalse(detail.toString().contains("987654321"));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void dockerPrivateGenericOutputLargerThanSixtyFourKiBUsesFormalComparisonNotDisplayCapture() throws Exception {
        var worker=worker();int number=create("FUNCTION",signature("int[]","int[]"));
        String value="["+"1,".repeat(39999)+"1]";
        db.update("UPDATE problem_test_cases SET input_data=?,expected_output=? WHERE problem_id=?","{\"args\":["+value+"]}",value,problemId(number));
        long id=tests.create(number,privateRequest("PYTHON","def sol(value):\n    return value\n"),solver);
        var detail=finish(worker,id);assertPrivateShape(detail);
        for(var result:detail.get("results"))assertEquals("ACCEPTED",result.get("status").asText());
        assertTrue(detail.toString().length()<1024);
    }

    private static TestRunService.Request privateRequest(String language,String source) {return new TestRunService.Request(language,source,null,true);}
    private static String endpoint(int number) {return "/api/problems/"+number+"/test-runs";}
    private static FunctionSignature signature(String parameter,String result) {return new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value",parameter)),result);}
    private Map<String,Object> run(long id) {return db.queryForMap("SELECT * FROM user_test_runs WHERE id=?",id);}
    private int create(String mode,FunctionSignature signature) {
        boolean function=mode.equals("FUNCTION"),array=function&&signature.parameters().getFirst().type().equals("int[]");
        String input1=function?array?"{\"args\":[[1]]}":"{\"args\":[987654321]}":"PRIVATE_STDIN_FIRST";
        String input2=function?array?"{\"args\":[[2]]}":"{\"args\":[876543210]}":"PRIVATE_STDIN_SECOND";
        String expected1=function?signature.returnType().equals("int[]")?"[1]":array?"1":"987654321":"PRIVATE_STDIN_FIRST";
        String expected2=function?signature.returnType().equals("int[]")?"[2]":array?"2":"876543210":"PRIVATE_STDIN_SECOND";
        var request=new ProblemRequests.Write("Private sample "+UUID.randomUUID(),"Public body","Public input","Public output","Fixture",
            "INITIATE",1,2000,256,mode,List.of(),List.of(new ProblemRequests.TestCase(input1,expected1),new ProblemRequests.TestCase(input2,expected2)),
            List.of("PYTHON","C"),List.of(),null,null,List.of(new ProblemRequests.ReferenceSolution("PYTHON","solution.py",
                function?"def sol(value):\n    return "+(array&&signature.returnType().equals("int")?"value[0]":"value")+"\n":"print(input())\n")),null,"PUBLIC",List.of(),false,signature);
        int number=problems.create(request,author);problemIds.add(problemId(number));
        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","private sample fixture"),author);return number;
    }
    private long problemId(int number) {return db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);}
    private Map<String,Object> statistics(int number) {
        var snapshot=new HashMap<String,Object>();
        snapshot.put("user",db.queryForMap("SELECT submit_count,solved_count FROM users WHERE id=?",solver.getUserId()));
        snapshot.put("problem",db.queryForMap("SELECT submission_count,accepted_count FROM problems WHERE problem_number=?",number));
        snapshot.put("submissions",db.queryForObject("SELECT COUNT(*) FROM submissions WHERE user_id=?",Long.class,solver.getUserId()));
        snapshot.put("solved",db.queryForObject("SELECT COUNT(*) FROM user_solved_problems WHERE user_id=?",Long.class,solver.getUserId()));return snapshot;
    }
    private void assertPrivateShape(JsonNode detail) {
        assertTrue(detail.get("privateSample").asBoolean());assertEquals(2,detail.get("testCount").asInt());
        assertEquals(2,detail.get("results").size());
        for(var result:detail.get("results")) {
            assertTrue(result.isObject());assertEquals(2,result.size());assertTrue(result.has("status"));assertTrue(result.has("timeMs"));
            assertTrue(result.get("timeMs").isIntegralNumber());assertTrue(result.get("timeMs").asInt()>=0);
        }
    }
    private TestRunWorker worker() {
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')",Integer.class),"A dedicated idle test queue is required");
        var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-private-sample-integration");
        assertNotNull(judge.imageIdentity());return new TestRunWorker(tests,judge);
    }
    private JsonNode finish(TestRunWorker worker,long id) throws Exception {
        for(int attempt=0;attempt<5;attempt++) {
            String status=db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,id);
            if(!Set.of("QUEUED","RUNNING").contains(status))break;worker.tick();
        }
        var detail=data(http("GET","/api/test-runs/"+id,null,solver));assertEquals("COMPLETED",detail.get("status").asText());return detail;
    }
    private JsonNode data(HttpResponse<String> response) {assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).get("data");}
    private HttpResponse<String> http(String method,String path,Object body,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(user!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private CustomUserDetails account(Role role) {
        String name="ps"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'private-sample-test-only',?)",name,role.name());
        var entity=User.builder().username(name).password("private-sample-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));ReflectionTestUtils.setField(entity,"role",role);
        var details=new CustomUserDetails(entity);accounts.add(details);return details;
    }
}
