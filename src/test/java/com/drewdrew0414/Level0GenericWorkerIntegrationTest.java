package com.drewdrew0414;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.submission.DockerJudge;
import com.drewdrew0414.domain.submission.JudgeWorker;
import com.drewdrew0414.domain.submission.Language;
import com.drewdrew0414.domain.submission.SubmissionService;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP 접수→DB 큐→격리 Docker 채점→캐시/풀이 원장을 함께 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"judge.enabled=false","curriculum.level0.enabled=false"})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
class Level0GenericWorkerIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired ProblemService problems;
    @Autowired SubmissionService submissions;
    @Autowired JwtTokenProvider jwt;
    @Autowired PlatformTransactionManager transactions;
    @Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<Long> problemIds=new ArrayList<>();
    private final List<CustomUserDetails> accounts=new ArrayList<>();
    private CustomUserDetails author,solver;

    @BeforeEach void setup() {
        // HTTP는 별도 DB 연결을 쓰므로 fixture를 커밋한 뒤 각 테스트가 만든 ID만 원자적으로 정리합니다.
        assertTrue(Set.of("cobweb_test","cobweb_feature_test","cobweb_revalidation_test")
            .contains(db.queryForObject("SELECT DATABASE()",String.class)),"A dedicated test database is required");
        author=account(Role.OPERATOR);solver=account(Role.USER);
    }
    @AfterEach void cleanupOwnFixtures() {
        new TransactionTemplate(transactions).executeWithoutResult(status->{
            if(author!=null) for(long id:problemIds)
                db.update("DELETE FROM problems WHERE id=? AND author_id=?",id,author.getUserId());
            for(var account:accounts)
                db.update("DELETE FROM users WHERE id=? AND username=?",account.getUserId(),account.getUsername());
        });
    }

    @Test void genericHttpSubmissionRunsStoredSignaturesThroughWorkerAndPreservesReuseScope() throws Exception {
        var judge=new DockerJudge(System.getenv().getOrDefault("GENERIC_JUDGE_IMAGE","cobweb-languages:local"),
            "/tmp/cobweb-generic-worker-test");
        assertNotNull(judge.imageIdentity(),"The configured generic judge image must exist");
        var worker=new JudgeWorker(submissions,judge);
        var arraySignature=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("numbers","int[]")),"int[]");
        String python="def sol(numbers):\n    return numbers[::-1]\ndef solution(numbers):\n    return sol(numbers)\n";
        int arrayProblem=create(arraySignature,List.of(
            new ProblemRequests.TestCase("{\"args\":[[1,-2,3]]}","[3,-2,1]"),
            new ProblemRequests.TestCase("{\"args\":[[]]}","[]"),
            new ProblemRequests.TestCase("{\"args\":[[-2147483648,2147483647]]}","[2147483647,-2147483648]")),python);
        var publicArray=data(http("GET","/api/problems/"+arrayProblem,null,null));
        assertEquals("GENERIC_V1",publicArray.get("functionAbi").asText());
        assertEquals("int[]",publicArray.get("functionSignature").get("returnType").asText());
        assertFalse(publicArray.has("tests"));assertFalse(publicArray.has("referenceSolutions"));

        long first=submit(arrayProblem,Language.PYTHON,python);
        assertEquals(python,db.queryForObject("SELECT source_code FROM submissions WHERE id=?",String.class,first));
        finish(worker,first,3);
        assertNull(db.queryForObject("SELECT reused_from_submission_id FROM submissions WHERE id=?",Long.class,first));
        String originalContext=db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,first);
        assertNotNull(originalContext);assertTrue(originalContext.matches("[0-9a-f]{64}"));
        long second=submit(arrayProblem,Language.PYTHON,python);
        finish(worker,second,3);
        assertEquals(first,db.queryForObject("SELECT reused_from_submission_id FROM submissions WHERE id=?",Long.class,second));
        assertEquals(originalContext,db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,second));
        assertEquals(1,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,solver.getUserId()));

        long arrayId=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,arrayProblem);
        var caseIds=db.queryForList("SELECT id FROM problem_test_cases WHERE problem_id=? ORDER BY display_order",Long.class,arrayId);
        var renamed=new FunctionSignature("solution",arraySignature.parameters(),arraySignature.returnType());
        // 캐시 방어를 독립적으로 검증하기 위해 테스트 ID/본문/정답/제한은 그대로 두고 계약만 바꿉니다.
        db.update("UPDATE problems SET function_signature=? WHERE id=?",renamed.toJson(json),arrayId);
        long third=submit(arrayProblem,Language.PYTHON,python);
        finish(worker,third,3);
        assertNull(db.queryForObject("SELECT reused_from_submission_id FROM submissions WHERE id=?",Long.class,third));
        assertNotEquals(originalContext,db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,third));
        assertEquals(caseIds,db.queryForList("SELECT id FROM problem_test_cases WHERE problem_id=? ORDER BY display_order",Long.class,arrayId));

        var stringSignature=new FunctionSignature("solution",List.of(new FunctionSignature.Parameter("enabled","bool")),"string");
        int stringProblem=create(stringSignature,List.of(
            new ProblemRequests.TestCase("{\"args\":[true]}","\"yes\""),
            new ProblemRequests.TestCase("{\"args\":[false]}","\"no\"")),
            "def solution(enabled):\n    return 'yes' if enabled else 'no'\n");
        long javascript=submit(stringProblem,Language.JAVASCRIPT,"function solution(enabled){return enabled ? 'yes' : 'no';}\n");
        finish(worker,javascript,2);
        assertNotEquals(originalContext,db.queryForObject("SELECT judge_context_sha256 FROM submissions WHERE id=?",String.class,javascript));
        assertEquals(2,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,solver.getUserId()));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM user_solved_problems WHERE user_id=?",Integer.class,solver.getUserId()));
        assertEquals(4,db.queryForObject("SELECT submit_count FROM users WHERE id=?",Integer.class,solver.getUserId()));
        assertEquals(3,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,arrayId));
    }
    private int create(FunctionSignature signature,List<ProblemRequests.TestCase> tests,String reference) {
        var example=tests.getFirst();
        var request=new ProblemRequests.Write("Generic worker "+UUID.randomUUID(),"Function body","Typed arguments",
            "Typed return","ASCII strings; signed 32-bit integers","INITIATE",1,2000,256,"FUNCTION",
            List.of(new ProblemRequests.Example(example.input(),example.expected(),"public example")),tests,
            List.of("PYTHON","JAVASCRIPT"),List.of(),null,null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","solution.py",reference)),null,"PUBLIC",List.of(),false,signature);
        int number=problems.create(request,author);
        problemIds.add(db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number));
        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","generic worker integration fixture"),author);
        return number;
    }
    private long submit(int number,Language language,String source) throws Exception {
        return data(http("POST","/api/submissions",Map.of("problemNumber",number,"language",language.name(),"sourceCode",source),solver)).asLong();
    }
    private void finish(JudgeWorker worker,long target,int expectedCases) throws Exception {
        long queued=db.queryForObject("SELECT COUNT(*) FROM submissions WHERE status='QUEUED' AND id<=?",Long.class,target);
        assertTrue(queued<=256,"The dedicated test queue is too large to drain safely: "+queued);
        for(long attempt=0;attempt<queued+3;attempt++) {
            String state=db.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,target);
            if(!Set.of("QUEUED","RUNNING").contains(state)) break;
            worker.tick();
        }
        var result=data(http("GET","/api/submissions/"+target,null,solver));
        assertEquals("ACCEPTED",result.get("status").asText(),result::toString);
        assertEquals(expectedCases,result.get("cases").size());
        for(var test:result.get("cases")) assertEquals("ACCEPTED",test.get("status").asText());
    }
    private JsonNode data(HttpResponse<String> response) {
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).get("data");
    }
    private HttpResponse<String> http(String method,String path,Object body,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(user!=null) request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        if(body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private CustomUserDetails account(Role role) {
        String name="gw"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'generic-worker-test-only',?)",name,role.name());
        var entity=User.builder().username(name).password("generic-worker-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",role);
        var account=new CustomUserDetails(entity);accounts.add(account);return account;
    }
}
