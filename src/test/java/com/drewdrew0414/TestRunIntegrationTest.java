package com.drewdrew0414;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.submission.DockerJudge;
import com.drewdrew0414.domain.submission.TestRunService;
import com.drewdrew0414.domain.submission.TestRunWorker;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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

/** 사용자가 보낸 입력 전용 큐의 HTTP 권한·상한·분리 원장·lease fencing을 검사합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"judge.enabled=false","curriculum.level0.enabled=false"})
@ActiveProfiles("test")
class TestRunIntegrationTest {
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
        assertTrue(Set.of("cobweb_test","cobweb_feature_test","cobweb_revalidation_test")
            .contains(db.queryForObject("SELECT DATABASE()",String.class)),"A dedicated test database is required");
        author=account(Role.OPERATOR);solver=account(Role.USER);other=account(Role.USER);
    }
    @AfterEach void cleanupOwnFixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            // user_test_runs has cascading FKs; remove only this test's authored problems/accounts.
            if(author!=null)for(long id:problemIds)db.update("DELETE FROM problems WHERE id=? AND author_id=?",id,author.getUserId());
            for(var account:accounts)db.update("DELETE FROM users WHERE id=? AND username=?",account.getUserId(),account.getUsername());
        });
    }

    @Test void httpQueueIsOwnerOnlyAndNeverChangesSubmissionOrSolvedStatistics() throws Exception {
        int number=create("FUNCTION");var before=statistics(number);
        var request=request("PYTHON","def sol(value):\n    return value + 1\n",List.of(new TestRunService.Case("{\"args\":[1]}","2")));
        assertEquals(401,http("POST",endpoint(number),request,null).statusCode());
        long id=data(http("POST",endpoint(number),request,solver)).asLong();
        var detail=data(http("GET","/api/test-runs/"+id,null,solver));
        assertEquals("QUEUED",detail.get("status").asText());assertEquals(0,detail.get("results").size());
        assertFalse(detail.has("sourceCode"));assertFalse(detail.has("source_code"));
        assertEquals(401,http("GET","/api/test-runs/"+id,null,null).statusCode());
        assertEquals(404,http("GET","/api/test-runs/"+id,null,other).statusCode());
        assertEquals(404,http("GET","/api/test-runs/"+id,null,author).statusCode(),"An operator is not the owner of a custom test run");
        assertEquals(404,http("GET","/api/test-runs/9223372036854775807",null,solver).statusCode());
        assertEquals(before,statistics(number));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE id=? AND user_id=?",Integer.class,id,solver.getUserId()));
        assertFalse(json.writeValueAsString(detail).contains("987654321"));
    }

    @Test void validationRejectsBadCaseCountsUtf8SizesTypesLanguagesAndRevokedProblemAccess() throws Exception {
        int number=create("FUNCTION");String source="def sol(value):\n    return value\n";
        var valid=new TestRunService.Case("{\"args\":[1]}","1");
        var invalid=new ArrayList<TestRunService.Request>();
        invalid.add(request("PYTHON",source,List.of()));
        invalid.add(request("PYTHON",source,List.of(valid,valid,valid,valid)));
        invalid.add(request("PYTHON",source,null));
        invalid.add(request("PYTHON"," ",List.of(valid)));
        invalid.add(request("PYTHON","가".repeat(21846),List.of(valid)));
        invalid.add(request("PYTHON",source,List.of(new TestRunService.Case("x".repeat(65537),"1"))));
        invalid.add(request("PYTHON",source,List.of(new TestRunService.Case("{\"args\":[1]}","가".repeat(21846)))));
        invalid.add(request("PYTHON",source,List.of(new TestRunService.Case("{\"args\":[\"wrong-type\"]}","1"))));
        invalid.add(request("PYTHON",source,List.of(new TestRunService.Case("{\"args\":[1]}","\"wrong-type\""))));
        invalid.add(request("PYTHON",source,List.of(new TestRunService.Case("{\"args\":[1],\"extra\":true}","1"))));
        invalid.add(request("SQL",source,List.of(valid)));
        invalid.add(request("UMJUNSIK",source,List.of(valid)));
        invalid.add(request("RUST",source,List.of(valid))); // Mode-compatible, but not allowed for this fixture.
        invalid.add(request("../../bin/sh",source,List.of(valid)));
        for(var body:invalid)assertEquals(400,http("POST",endpoint(number),body,solver).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
        long id=data(http("POST",endpoint(number),request("PYTHON",source,List.of(valid,valid,valid)),solver)).asLong();
        assertEquals(3,db.queryForObject("SELECT JSON_LENGTH(input_cases) FROM user_test_runs WHERE id=?",Integer.class,id));
        problems.setVisibility(number,new ProblemRequests.Visibility("PRIVATE","custom test access revoked"),author);
        assertEquals(404,http("GET","/api/test-runs/"+id,null,solver).statusCode());
        assertEquals(404,http("POST",endpoint(number),request("PYTHON",source,List.of(valid)),solver).statusCode());
    }

    @Test void perAccountPendingAndPerMinuteLimitsAreEnforcedWithoutCreatingRejectedRows() throws Exception {
        int number=create("FUNCTION");var body=request("PYTHON","def sol(value):\n    return value\n",List.of(new TestRunService.Case("{\"args\":[1]}","1")));
        data(http("POST",endpoint(number),body,solver));data(http("POST",endpoint(number),body,solver));
        assertEquals(429,http("POST",endpoint(number),body,solver).statusCode());
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
        db.update("UPDATE user_test_runs SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6) WHERE user_id=?",solver.getUserId());
        for(int index=2;index<10;index++){
            long id=data(http("POST",endpoint(number),body,solver)).asLong();
            db.update("UPDATE user_test_runs SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6) WHERE id=? AND user_id=?",id,solver.getUserId());
        }
        assertEquals(429,http("POST",endpoint(number),body,solver).statusCode());
        assertEquals(10,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
    }

    @Test void globalQueueLimitIsCheckedAcrossAccounts() throws Exception {
        int number=create("FUNCTION");long problemId=problemId(number);
        String cases=json.writeValueAsString(List.of(new TestRunService.Case("{\"args\":[1]}","1")));
        for(int index=0;index<60;index++)db.update("""
            INSERT INTO user_test_runs(user_id,problem_id,language,source_code,execution_mode,function_signature,
                time_limit_ms,memory_limit_mb,input_cases) VALUES(?,?,'PYTHON',?,'FUNCTION',?,2000,256,?)
            """,other.getUserId(),problemId,"def sol(value):\n    return value\n",signature().toJson(json),cases);
        var body=request("PYTHON","def sol(value):\n    return value\n",List.of(new TestRunService.Case("{\"args\":[1]}","1")));
        assertEquals(429,http("POST",endpoint(number),body,solver).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
    }

    @Test void escapedCaseEnvelopeHasItsOwnByteLimitWithoutReturningDatabaseErrors() throws Exception {
        int number=create("STDIN");
        var expanded=new TestRunService.Case("\u0001".repeat(65536),"\u0002".repeat(65536));
        var response=http("POST",endpoint(number),request("PYTHON","print('test')",List.of(expanded,expanded,expanded)),solver);
        assertEquals(400,response.statusCode(),response.body());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE user_id=?",Integer.class,solver.getUserId()));
    }

    @Test void claimTokensAndExpiredLeasesFenceOffOldCompletionsAndNeverRetryTimedOutJobs() throws Exception {
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')",Integer.class),"A dedicated idle test queue is required");
        int number=create("FUNCTION");var body=request("PYTHON","def sol(value):\n    return value\n",List.of(new TestRunService.Case("{\"args\":[1]}","1")));
        long id=data(http("POST",endpoint(number),body,solver)).asLong();var claimed=tests.claim();
        assertNotNull(claimed);assertEquals(id,((Number)claimed.get("id")).longValue());assertTrue(tests.current(claimed));
        var result=List.of(new TestRunService.Result("{\"args\":[1]}","1","1","ACCEPTED",1,null));
        var wrong=new HashMap<String,Object>(claimed);wrong.put("judge_token",UUID.randomUUID().toString());
        assertFalse(tests.current(wrong));tests.finish(wrong,result,false);
        assertEquals("RUNNING",db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,id));
        assertNull(db.queryForObject("SELECT result_json FROM user_test_runs WHERE id=?",String.class,id));
        db.update("UPDATE user_test_runs SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",id);
        assertFalse(tests.current(claimed));tests.finish(claimed,result,false);
        assertNull(db.queryForObject("SELECT result_json FROM user_test_runs WHERE id=?",String.class,id));
        tests.expire();tests.finish(claimed,result,false);
        assertEquals("SYSTEM_ERROR",db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,id));
        assertNull(db.queryForObject("SELECT judge_token FROM user_test_runs WHERE id=?",String.class,id));assertNull(tests.claim());
        long queued=data(http("POST",endpoint(number),body,solver)).asLong();
        db.update("UPDATE user_test_runs SET created_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 16 MINUTE) WHERE id=?",queued);
        tests.expire();assertEquals("SYSTEM_ERROR",db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,queued));assertNull(tests.claim());
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void dockerFunctionUsesOnlyUserInputsReturnsActualForCorrectAndWrongExpectedAndLeavesFormalStatsUntouched() throws Exception {
        int number=create("FUNCTION");var before=statistics(number);var worker=worker();
        long id=data(http("POST",endpoint(number),request("PYTHON","def sol(value):\n    return value + 1\n",List.of(
            new TestRunService.Case("{\"args\":[2]}","3"),new TestRunService.Case("{\"args\":[4]}","999"))),solver)).asLong();
        var detail=finish(worker,id);assertEquals("COMPLETED",detail.get("status").asText());assertEquals(2,detail.get("results").size());
        JsonNode correct=detail.get("results").get(0),wrong=detail.get("results").get(1);
        assertEquals("ACCEPTED",correct.get("status").asText());assertEquals("3",correct.get("actual").asText().strip());
        assertEquals("WRONG_ANSWER",wrong.get("status").asText());assertEquals("5",wrong.get("actual").asText().strip());assertEquals("999",wrong.get("expected").asText());
        assertFalse(detail.toString().contains("987654321"));assertFalse(detail.toString().contains("PRIVATE_STDIN_CASE"));assertEquals(before,statistics(number));
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void dockerStdinReturnsUserOutputAndCompileAndRuntimeFailuresHaveOnlySafeDiagnostics() throws Exception {
        var worker=worker();int stdin=create("STDIN");var before=statistics(stdin);
        long id=data(http("POST",endpoint(stdin),request("PYTHON","print(input().upper())\n",List.of(new TestRunService.Case("hello\n","HELLO\n"))),solver)).asLong();
        var output=finish(worker,id).get("results").get(0);
        assertEquals("ACCEPTED",output.get("status").asText());assertEquals("HELLO\n",output.get("actual").asText());
        assertEquals(before,statistics(stdin));
        long broken=data(http("POST",endpoint(stdin),request("C","int main( {\n",List.of(new TestRunService.Case("user-only-input","unused"))),solver)).asLong();
        var compile=finish(worker,broken).get("results").get(0);
        assertEquals("COMPILATION_ERROR",compile.get("status").asText());assertEquals("COMPILE",compile.get("diagnostic").get("phase").asText());
        int function=create("FUNCTION");
        long crashing=data(http("POST",endpoint(function),request("PYTHON","def sol(value):\n    return 1 // 0\n",List.of(new TestRunService.Case("{\"args\":[2]}","0"))),solver)).asLong();
        var runtime=finish(worker,crashing).get("results").get(0);
        assertEquals("RUNTIME_ERROR",runtime.get("status").asText());assertEquals("RUN",runtime.get("diagnostic").get("phase").asText());
        for(var result:List.of(compile,runtime)){
            String diagnostic=result.get("diagnostic").toString();
            assertFalse(diagnostic.contains("/tmp/"));assertFalse(diagnostic.contains("/source/"));assertFalse(diagnostic.contains("987654321"));assertFalse(diagnostic.contains("user-only-input"));
        }
    }

    @Test @EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void invalidUtf8OutputCannotExpandPastThePublicDisplayByteLimit() throws Exception {
        var worker=worker();int number=create("STDIN");
        long id=data(http("POST",endpoint(number),request("PYTHON",
            "import sys\nsys.stdout.buffer.write(bytes([255])*65536)\n",List.of(new TestRunService.Case("",""))),solver)).asLong();
        var output=finish(worker,id).get("results").get(0);
        assertEquals("RUNTIME_ERROR",output.get("status").asText());
        assertTrue(output.get("actual").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=65536);
        assertEquals("OUTPUT_LIMIT",output.get("diagnostic").get("errorType").asText());
    }

    private static TestRunService.Request request(String language,String source,List<TestRunService.Case> cases) {return new TestRunService.Request(language,source,cases);}
    private static String endpoint(int number) {return "/api/problems/"+number+"/test-runs";}
    private static FunctionSignature signature() {return new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("value","int")),"int");}
    private int create(String mode) {
        boolean function=mode.equals("FUNCTION");String input=function?"{\"args\":[987654321]}":"PRIVATE_STDIN_CASE";
        String expected=function?"987654321":"PRIVATE_EXPECTED_MARKER";
        var request=new ProblemRequests.Write("Custom run "+UUID.randomUUID(),"Public body","Public input","Public output","Fixture",
            "INITIATE",1,2000,256,mode,List.of(new ProblemRequests.Example(function?"{\"args\":[1]}":"sample",function?"1":"sample","Public example")),
            List.of(new ProblemRequests.TestCase(input,expected)),List.of("PYTHON","C"),List.of(),null,null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","solution.py",function?"def sol(value):\n    return value\n":"print(input())\n")),null,"PUBLIC",List.of(),false,function?signature():null);
        int number=problems.create(request,author);problemIds.add(problemId(number));
        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","custom run fixture"),author);return number;
    }
    private long problemId(int number) {return db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);}
    private Map<String,Object> statistics(int number) {
        var snapshot=new HashMap<String,Object>();
        snapshot.put("user",db.queryForMap("SELECT submit_count,solved_count FROM users WHERE id=?",solver.getUserId()));
        snapshot.put("problem",db.queryForMap("SELECT submission_count,accepted_count FROM problems WHERE problem_number=?",number));
        snapshot.put("submissions",db.queryForObject("SELECT COUNT(*) FROM submissions WHERE user_id=?",Long.class,solver.getUserId()));
        snapshot.put("solved",db.queryForObject("SELECT COUNT(*) FROM user_solved_problems WHERE user_id=?",Long.class,solver.getUserId()));return snapshot;
    }
    private TestRunWorker worker() {
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_test_runs WHERE status IN ('QUEUED','RUNNING')",Integer.class),"A dedicated idle test queue is required");
        var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),"/tmp/cobweb-custom-run-integration");
        assertNotNull(judge.imageIdentity());return new TestRunWorker(tests,judge);
    }
    private JsonNode finish(TestRunWorker worker,long id) throws Exception {
        for(int attempt=0;attempt<5;attempt++){
            String status=db.queryForObject("SELECT status FROM user_test_runs WHERE id=?",String.class,id);
            if(!Set.of("QUEUED","RUNNING").contains(status))break;worker.tick();
        }
        var result=data(http("GET","/api/test-runs/"+id,null,solver));
        assertEquals("COMPLETED",result.get("status").asText(),result::toString);return result;
    }
    private JsonNode data(HttpResponse<String> response) {
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).get("data");
    }
    private HttpResponse<String> http(String method,String path,Object body,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(user!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private CustomUserDetails account(Role role) {
        String name="tr"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'custom-run-test-only',?)",name,role.name());
        var entity=User.builder().username(name).password("custom-run-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));ReflectionTestUtils.setField(entity,"role",role);
        var details=new CustomUserDetails(entity);accounts.add(details);return details;
    }
}
