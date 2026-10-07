package com.drewdrew0414;

import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.submission.*;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 MySQL과 HTTP 서버에서 마이그레이션, 권한, 검수, 큐, 집계를 함께 검증합니다.
 * 사용자마다 고유 아이디를 사용하므로 테스트를 재실행해도 기존 테스트 기록과 충돌하지 않습니다.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MvpIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired SubmissionService submissions;
    @Autowired JwtTokenProvider jwt;
    @Autowired DockerJudge dockerJudge;
    @Autowired ObjectMapper json;
    @Autowired com.drewdrew0414.domain.user.service.AuthService auth;
    @Autowired com.drewdrew0414.domain.user.service.PasswordResetService passwordReset;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired com.drewdrew0414.domain.user.service.EmailVerificationService emailVerification;
    @Autowired com.drewdrew0414.domain.user.service.AuthRateLimitService authRateLimit;
    @Autowired com.drewdrew0414.global.security.EmailCodeHash emailCodeHash;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private CustomUserDetails author,other,admin;
    @BeforeEach void setup(){
        author=user("USER");other=user("USER");admin=user("OPERATOR");
    }
    CustomUserDetails user(String role){
        String name="u"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'unused',?)",name,role);
        long id=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        User u=User.builder().username(name).password("unused").build();
        ReflectionTestUtils.setField(u,"id",id);
        ReflectionTestUtils.setField(u,"role",com.drewdrew0414.domain.user.entity.Role.valueOf(role));
        return new CustomUserDetails(u);
    }
    ProblemRequests.Write draft(){
        return new ProblemRequests.Write("테스트 문제","본문","입력","출력","제한","ELITE",5,1000,128,"FUNCTION",
            List.of(new ProblemRequests.Example("example","true","설명")),
            List.of(new ProblemRequests.TestCase(functionInput(),"true")));
    }
    private String functionInput() {
        String row="["+String.join(",",Collections.nCopies(15,"0"))+"]";
        return "{\"matrix\":["+String.join(",",Collections.nCopies(15,row))+"],\"target\":[7,7]}";
    }
    private ProblemRequests.Write withFunctionTest(String input,String expected) {
        var r=draft();
        return new ProblemRequests.Write(r.title(),r.description(),r.inputDescription(),r.outputDescription(),
            r.constraints(),r.rankType(),r.rankInt(),r.timeLimitMs(),r.memoryLimitMb(),r.executionMode(),
            r.examples(),List.of(new ProblemRequests.TestCase(input,expected)));
    }
    @Test void functionTestsRequireTheSharedLanguageInputContract() {
        int number=problems.create(draft(),author);
        for (var invalid:List.of(
            withFunctionTest("{\"matrix\":[],\"target\":[7,7]}","true"),
            withFunctionTest(functionInput().replaceFirst("0","2"),"true"),
            withFunctionTest(functionInput().replace("[7,7]","[15,7]"),"true"),
            withFunctionTest(functionInput(),"TRUE"))) {
            var createError=assertThrows(ResponseStatusException.class,()->problems.create(invalid,author));
            assertEquals(400,createError.getStatusCode().value());
            var updateError=assertThrows(ResponseStatusException.class,()->problems.update(number,invalid,author));
            assertEquals(400,updateError.getStatusCode().value());
        }
        assertEquals("FUNCTION",problems.load(number,author).get("execution_mode"));
    }
    @Test void legacyFunctionTestsStayPythonOnlyWhileCompatibleProblemsKeepAllLanguages() throws Exception {
        assertEquals(12,((List<?>)problems.detail(1000,other).get("languages")).size());
        int number=problems.create(draft(),author);
        long pid=((Number)problems.load(number,author).get("id")).longValue();
        db.update("UPDATE problem_test_cases SET input_data=? WHERE problem_id=?",
            "{\"matrix\":[],\"target\":[7,7]}",pid);
        db.update("UPDATE problems SET function_abi_compatible=FALSE WHERE id=?",pid);
        problems.requestReview(number,author);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","레거시 검증"),admin);
        var detail=json.readTree(get("/api/problems/"+number,null).body()).get("data");
        assertEquals(1,detail.get("languages").size());
        assertEquals("PYTHON",detail.get("languages").get(0).asText());
        var error=assertThrows(ResponseStatusException.class,()->submissions.submit(number,
            "bool sol(const int m[15][15],int r,int c){return true;}",Language.C,other));
        assertEquals(400,error.getStatusCode().value());
        long accepted=submissions.submit(number,"def sol(matrix,target): return True",Language.PYTHON,other);
        db.update("UPDATE submissions SET status='SYSTEM_ERROR' WHERE id=?",accepted);
    }
    HttpResponse<String> get(String path,CustomUserDetails user)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(user!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void permissionsReviewAndPrivateTests()throws Exception{
        int number=problems.create(draft(),author);
        assertTrue(number>=1000);
        assertEquals(404,get("/api/problems/"+number,other).statusCode());
        assertEquals(404,get("/api/problems/"+number,null).statusCode());
        assertEquals(200,get("/api/problems/"+number,author).statusCode());
        assertThrows(ResponseStatusException.class,()->problems.update(number,draft(),other));
        problems.requestReview(number,author);
        assertThrows(ResponseStatusException.class,()->problems.review(number,new ProblemRequests.Review("PUBLISHED",""),author));
        problems.review(number,new ProblemRequests.Review("PUBLISHED","확인"),admin);
        var response=get("/api/problems/"+number,null);
        assertEquals(200,response.statusCode());
        assertFalse(response.body().contains("expected_output"));
        assertFalse(response.body().contains("test_cases"));
        assertTrue(response.body().contains("examples"));
        assertThrows(ResponseStatusException.class,()->problems.update(number,draft(),author));
    }
    @Test void queueFinalizationIsIdempotentAndSolvesCountOnce(){
        int number=problems.create(draft(),author);
        problems.requestReview(number,author);
        problems.review(number,new ProblemRequests.Review("PUBLISHED",""),admin);
        long pid=((Number)problems.load(number,author).get("id")).longValue();
        for(int i=0;i<2;i++){
            long id=submissions.submit(number,"def sol(matrix,target): return True",other);
            // 다른 테스트의 QUEUED 작업과 독립적으로 이 제출만 활성화합니다.
            String token=UUID.randomUUID().toString();
            db.update("UPDATE submissions SET status='RUNNING',judge_token=?,claimed_at=CURRENT_TIMESTAMP WHERE id=?",token,id);
            var s=db.queryForMap("SELECT * FROM submissions WHERE id=?",id);
            var test=submissions.cases(pid).getFirst();
            var result=List.of(new SubmissionService.JudgeResult(((Number)test.get("id")).longValue(),"ACCEPTED",10));
            submissions.finish(s,result);submissions.finish(s,result);
            assertEquals("ACCEPTED",submissions.detail(id,other).get("status"));
            assertThrows(ResponseStatusException.class,()->submissions.detail(id,author));
        }
        assertEquals(1,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,other.getUserId()));
        assertEquals(2,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,pid));
        assertEquals(2,db.queryForObject("SELECT submit_count FROM users WHERE id=?",Integer.class,other.getUserId()));
    }
    @Test void publicEntryPointsUseTheSameSinglePage() throws Exception {
        // 실제 정적 파일은 하나만 있어야 하며 이전 주소도 같은 화면으로 연결되어야 합니다.
        var canonical=get("/index.html",null);
        assertEquals(200,canonical.statusCode());
        assertTrue(canonical.body().contains("name=\"verificationCode\""));
        assertTrue(canonical.body().contains("src=\"/app.js\""));
        for(String path:List.of("/","/app.html")) {
            var response=get(path,null);
            assertEquals(200,response.statusCode(),path);
            assertEquals(canonical.body(),response.body(),path);
        }
        assertFalse(new org.springframework.core.io.ClassPathResource("static/app.html").exists());
    }
    @Test void unauthenticatedSubmissionAndInvalidInputAreRejected()throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/submissions"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"problemNumber\":1000,\"sourceCode\":\"print(1)\"}")).build();
        int status=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode();
        assertTrue(status==401||status==403);
        assertEquals(200,get("/app.html",null).statusCode());
        assertEquals(200,get("/api/ranking",null).statusCode());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void chainLaserHttpSubmissionRunsAllPrivateCases() throws Exception {
        // 테스트 정답 구현은 발사점을 BFS로 순회합니다. 시드 정답 생성의 행/열 확장 방식과 독립적입니다.
        String code="""
            from collections import deque
            def sol(matrix, target):
                queue = deque([(7, 7)])
                emitted = {(7, 7)}
                while queue:
                    r, c = queue.popleft()
                    if (r, c) == target:
                        return True
                    for dr, dc in ((1,0),(-1,0),(0,1),(0,-1)):
                        nr, nc = r + dr, c + dc
                        while 0 <= nr < 15 and 0 <= nc < 15:
                            if (nr, nc) == target:
                                return True
                            if matrix[nr][nc] == 1 and (nr,nc) not in emitted:
                                emitted.add((nr,nc))
                                queue.append((nr,nc))
                            nr, nc = nr + dr, nc + dc
                return False
            """;
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/submissions"))
            .header("Content-Type","application/json")
            .header("Authorization","Bearer "+jwt.generateAccessToken(other.getUserId(),other.getUsername(),"USER",0))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("problemNumber",1000,"sourceCode",code)))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());
        long id=json.readTree(response.body()).get("data").asLong();
        new JudgeWorker(submissions,dockerJudge).tick();
        var result=submissions.detail(id,other);
        assertEquals("ACCEPTED",result.get("status"),result.toString());
        assertEquals(10,((List<?>)result.get("cases")).size());
        assertEquals(1,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,other.getUserId()));
    }
    @Test void simultaneousCreationAllocatesDistinctNumbers() throws Exception {
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var first=executor.submit(()->problems.create(draft(),author));
            var second=executor.submit(()->problems.create(draft(),other));
            assertNotEquals(first.get(),second.get());
        }
    }
    @Test void profileUpdateValidatesGithubAndHidesEmail() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/me"))
            .header("Authorization","Bearer "+jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER",0))
            .header("Content-Type","application/json")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"bio\":\"안녕하세요\",\"githubUrl\":\"javascript:alert(1)\"}")).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(400,response.statusCode());
        var profile=get("/api/me",author);
        assertEquals(200,profile.statusCode());
        assertFalse(profile.body().contains("password"));
        assertFalse(profile.body().contains("email"));
    }
    @Test void tokenMustMatchCurrentAccountId() throws Exception {
        // 삭제 후 같은 아이디로 재가입한 계정을 이전 계정의 토큰으로 인증하면 안 됩니다.
        String token=jwt.generateAccessToken(other.getUserId(),author.getUsername(),"USER",0);
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",token).statusCode()));
    }
    @Test void missingAccountTokenDoesNotBreakPublicRequests() throws Exception {
        String token=jwt.generateAccessToken(author.getUserId(),"missing_"+UUID.randomUUID(),"USER",0);
        assertEquals(200,getWithToken("/api/problems",token).statusCode());
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",token).statusCode()));
    }
    HttpResponse<String> getWithToken(String path,String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    String refreshCookie(org.springframework.mock.web.MockHttpServletResponse response) {
        return response.getHeader("Set-Cookie").split(";",2)[0].substring("refreshToken=".length());
    }
    @Test void refreshTokensAreUniqueEvenWhenIssuedImmediately(){
        var tokens=new HashSet<String>();
        for(int i=0;i<20;i++)assertTrue(tokens.add(jwt.generateRefreshToken(author.getUserId())));
    }
    @Test void logoutImmediatelyRevokesAccessToken() throws Exception {
        String oldToken=jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER",0);
        assertEquals(200,getWithToken("/api/me",oldToken).statusCode());
        auth.logout(author.getUserId(),new org.springframework.mock.web.MockHttpServletResponse());
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",oldToken).statusCode()));
        String newToken=jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER",1);
        assertEquals(200,getWithToken("/api/me",newToken).statusCode());
    }
    @Test void passwordResetImmediatelyRevokesAccessToken() throws Exception {
        String oldToken=jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER",0);
        assertEquals(200,getWithToken("/api/me",oldToken).statusCode());
        String resetToken=UUID.randomUUID().toString();
        db.update("INSERT INTO password_reset(user_id,reset_token,expires_at) VALUES(?,?,?)",
            author.getUserId(),com.drewdrew0414.global.security.TokenHash.sha256(resetToken),
            java.time.LocalDateTime.now().plusMinutes(30));
        passwordReset.confirmReset(resetToken,"new-password");
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",oldToken).statusCode()));
    }
    @Test void authRateLimitIsAtomicAcrossConcurrentRequests() throws Exception {
        String identity="rate-"+UUID.randomUUID();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->authRateLimit.hit("test",identity,1,java.time.Duration.ofMinutes(10)));
            var second=executor.submit(()->authRateLimit.hit("test",identity,1,java.time.Duration.ofMinutes(10)));
            int allowed=0,blocked=0;
            for(var future:List.of(first,second)) {
                try { future.get(); allowed++; }
                catch(java.util.concurrent.ExecutionException e) {
                    assertInstanceOf(com.drewdrew0414.domain.user.exception.TooManyAuthAttemptsException.class,e.getCause());
                    blocked++;
                }
            }
            assertEquals(1,allowed);
            assertEquals(1,blocked);
        }
    }
    @Test void verificationAttemptsAreLimitedEvenWhenCodeIsWrong() {
        String email="rate-"+UUID.randomUUID()+"@example.com";
        for(int i=0;i<10;i++)assertThrows(
            com.drewdrew0414.domain.user.exception.EmailVerificationNotFoundException.class,
            ()->emailVerification.verifyCode(email,"00000000",
                com.drewdrew0414.domain.user.entity.VerificationPurpose.JOIN));
        assertThrows(com.drewdrew0414.domain.user.exception.TooManyAuthAttemptsException.class,
            ()->emailVerification.verifyCode(email,"00000000",
                com.drewdrew0414.domain.user.entity.VerificationPurpose.JOIN));
    }
    @Test void concurrentCodeExchangeIssuesOnlyOneResetToken() throws Exception {
        String email="code-"+UUID.randomUUID()+"@example.com";
        db.update("UPDATE users SET email=? WHERE id=?",email,author.getUserId());
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,expires_at) VALUES(?,?,'PASSWORD_RESET',?)",
            email,emailCodeHash.hash(email,com.drewdrew0414.domain.user.entity.VerificationPurpose.PASSWORD_RESET,"12345678"),
            java.time.LocalDateTime.now().plusMinutes(5));
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->passwordReset.verifyCodeAndIssueToken(email,"12345678"));
            var second=executor.submit(()->passwordReset.verifyCodeAndIssueToken(email,"12345678"));
            int allowed=0,blocked=0;
            for(var future:List.of(first,second)) {
                try { assertNotNull(future.get()); allowed++; }
                catch(java.util.concurrent.ExecutionException e) {
                    assertTrue(e.getCause() instanceof com.drewdrew0414.domain.user.exception.EmailVerificationNotFoundException
                        || e.getCause() instanceof com.drewdrew0414.domain.user.exception.EmailNotVerifiedException,e.getCause().toString());
                    blocked++;
                }
            }
            assertEquals(1,allowed);
            assertEquals(1,blocked);
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM password_reset WHERE user_id=?",Integer.class,author.getUserId()));
    }
    @Test void refreshAndResetTokensAreNotStoredInPlaintext() {
        db.update("UPDATE users SET password=?,email=? WHERE id=?",encoder.encode("correct-password"),
            "member-"+UUID.randomUUID()+"@example.com",author.getUserId());
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        auth.login(author.getUsername(),"correct-password","127.0.0.1","test",response);
        String rawRefresh=refreshCookie(response);
        String storedRefresh=db.queryForObject("SELECT token FROM refresh_token WHERE user_id=?",String.class,author.getUserId());
        assertNotEquals(rawRefresh,storedRefresh);
        assertEquals(com.drewdrew0414.global.security.TokenHash.sha256(rawRefresh),storedRefresh);
        auth.reissue(rawRefresh,new org.springframework.mock.web.MockHttpServletResponse());

        String email=db.queryForObject("SELECT email FROM users WHERE id=?",String.class,author.getUserId());
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,is_verified,expires_at) VALUES(?,?,'PASSWORD_RESET',TRUE,?)",
            email,emailCodeHash.hash(email,com.drewdrew0414.domain.user.entity.VerificationPurpose.PASSWORD_RESET,"12345678"),
            java.time.LocalDateTime.now().plusMinutes(5));
        String rawReset=passwordReset.verifyCodeAndIssueToken(email,"12345678");
        String storedReset=db.queryForObject("SELECT reset_token FROM password_reset WHERE user_id=? ORDER BY id DESC LIMIT 1",
            String.class,author.getUserId());
        assertNotEquals(rawReset,storedReset);
        assertEquals(com.drewdrew0414.global.security.TokenHash.sha256(rawReset),storedReset);
        passwordReset.confirmReset(rawReset,"new-password");
        assertThrows(com.drewdrew0414.domain.user.exception.InvalidResetTokenException.class,()->
            passwordReset.confirmReset(rawReset,"replayed-password"));
    }
    @Test void failedLoginIsRecordedDespiteAuthenticationException(){
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        assertThrows(com.drewdrew0414.domain.user.exception.PasswordMismatchException.class,()->
            auth.login(author.getUsername(),"wrong-password","127.0.0.1","test",new org.springframework.mock.web.MockHttpServletResponse()));
        assertEquals(1,db.queryForObject("SELECT login_failed_count FROM users WHERE id=?",Integer.class,author.getUserId()));
    }
    @Test void oversizedLoginNameIsRejectedBeforeAuditInsert() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                "username","x".repeat(17),"password","wrong-password")))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(400,response.statusCode(),response.body());
    }
    @Test void stoppedAccountCannotRefreshTokens(){
        String token=jwt.generateRefreshToken(author.getUserId());
        db.update("INSERT INTO refresh_token(user_id,token,expires_at) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))",
            author.getUserId(),com.drewdrew0414.global.security.TokenHash.sha256(token));
        db.update("UPDATE users SET status='STOPPED' WHERE id=?",author.getUserId());
        assertThrows(com.drewdrew0414.domain.user.exception.BannedUserException.class,()->
            auth.reissue(token,new org.springframework.mock.web.MockHttpServletResponse()));
    }
    @Test void nullTestCaseIsBadRequestInsteadOfServerError() throws Exception {
        var body=json.readTree(json.writeValueAsString(draft()));
        ((tools.jackson.databind.node.ObjectNode)body).putArray("tests").addNull();
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/problems"))
            .header("Content-Type","application/json")
            .header("Authorization","Bearer "+jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER",0))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        assertEquals(400,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void invalidNumericParametersReturnBadRequest() throws Exception {
        assertEquals(400,get("/api/problems/not-a-number",null).statusCode());
        assertEquals(400,get("/api/ranking?page=abc",null).statusCode());
    }
    @Test void refreshRotationInvalidatesPreviousToken(){
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        var loginResponse=new org.springframework.mock.web.MockHttpServletResponse();
        auth.login(author.getUsername(),"correct-password","127.0.0.1","test",loginResponse);
        String previous=refreshCookie(loginResponse);
        var rotatedResponse=new org.springframework.mock.web.MockHttpServletResponse();
        auth.reissue(previous,rotatedResponse);
        String current=refreshCookie(rotatedResponse);
        assertNotEquals(previous,current);
        assertEquals(com.drewdrew0414.global.security.TokenHash.sha256(current),
            db.queryForObject("SELECT token FROM refresh_token WHERE user_id=?",String.class,author.getUserId()));
        assertThrows(com.drewdrew0414.domain.user.exception.InvalidTokenException.class,()->
            auth.reissue(previous,new org.springframework.mock.web.MockHttpServletResponse()));
    }
    @Test void simultaneousRefreshUseHasOneWinner() throws Exception {
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        var loginResponse=new org.springframework.mock.web.MockHttpServletResponse();
        auth.login(author.getUsername(),"correct-password","127.0.0.1","test",loginResponse);
        String original=refreshCookie(loginResponse);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<String> attempt=()->{
                start.await();
                try {
                    var response=new org.springframework.mock.web.MockHttpServletResponse();
                    auth.reissue(original,response);
                    return refreshCookie(response);
                } catch(com.drewdrew0414.domain.user.exception.InvalidTokenException expected) {
                    return null;
                }
            };
            var first=executor.submit(attempt);
            var second=executor.submit(attempt);
            start.countDown();
            String a=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
            String b=second.get(15,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1,(a==null?0:1)+(b==null?0:1));
            String rotated=a!=null?a:b;
            assertEquals(com.drewdrew0414.global.security.TokenHash.sha256(rotated),
                db.queryForObject("SELECT token FROM refresh_token WHERE user_id=?",String.class,author.getUserId()));
        }
    }
    @Test void clientCannotForgeLoginAuditAddress() throws Exception {
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
            .header("Content-Type","application/json")
            .header("X-Forwarded-For","203.0.113.77")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                Map.of("username",author.getUsername(),"password","wrong-password")))).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode()>=400 && response.statusCode()<500,response.body());
        String recorded=db.queryForObject("SELECT client_ip FROM users_log WHERE user_id=? ORDER BY id DESC LIMIT 1",
                String.class,author.getUserId());
        assertEquals("127.0.0.1",recorded,"인증되지 않은 전달 헤더를 감사 로그 IP로 신뢰하면 안 됩니다.");
    }
    @Test void verifiedEmailDoesNotAuthorizeAnUnrelatedSignupRequest() throws Exception {
        String email="owner-"+UUID.randomUUID()+"@example.invalid";
        String name="s"+UUID.randomUUID().toString().replace("-", "").substring(0,12);
        // 실제 메일 전송 대신 테스트용 코드만 저장하고 소유자의 코드 확인을 서비스로 수행합니다.
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,expires_at) VALUES(?,?,'JOIN',?)",
            email,emailCodeHash.hash(email,com.drewdrew0414.domain.user.entity.VerificationPurpose.JOIN,"83920174"),
            java.time.LocalDateTime.now().plusMinutes(5));
        emailVerification.verifyCode(email,"83920174",com.drewdrew0414.domain.user.entity.VerificationPurpose.JOIN);
        var body=new HashMap<String,Object>(Map.of("username",name,"email",email,"password","new-password"));
        // 다른 익명 브라우저는 이메일 주소만 알 뿐 인증 코드를 갖고 있지 않습니다.
        assertEquals(400,signup(body).statusCode(),"이메일의 전역 verified 플래그만으로 타인 요청이 가입에 성공했습니다.");
        body.put("verificationCode","00000000");
        assertEquals(400,signup(body).statusCode());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,email));
        body.put("verificationCode","83920174");
        var legitimate=signup(body);
        assertEquals(200,legitimate.statusCode(),legitimate.body());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM users WHERE email=?",Integer.class,email));
    }
    private ProblemRequests.Write languageDraft(String mode) {
        String input=mode.equals("SQL")?"CREATE TABLE numbers(v INTEGER); INSERT INTO numbers VALUES(2),(3);":
            mode.equals("FUNCTION")?functionInput():"2 3\n";
        return new ProblemRequests.Write("언어 검증 "+mode,"본문","입력","출력","제한","ELITE",5,3000,256,mode,
            List.of(new ProblemRequests.Example("2 3","5","")),
            List.of(new ProblemRequests.TestCase(input,mode.equals("FUNCTION")?"true":"5")));
    }
    private int publishedLanguageProblem(String mode) {
        int number=problems.create(languageDraft(mode),author);
        problems.requestReview(number,author);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","검증"),admin);
        return number;
    }
    private HttpResponse<String> submitLanguage(int number,String language,String source) throws Exception {
        var body=new HashMap<String,Object>(Map.of("problemNumber",number,"sourceCode",source));
        if(language!=null)body.put("language",language);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/submissions"))
            .header("Content-Type","application/json")
            .header("Authorization","Bearer "+jwt.generateAccessToken(other.getUserId(),other.getUsername(),"USER",0))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void languageCatalogAndModeChangesStayConsistent() throws Exception {
        var response=get("/api/languages",null);
        assertEquals(200,response.statusCode());
        assertEquals(13,json.readTree(response.body()).get("data").size());
        int number=problems.create(languageDraft("STDIN"),author);
        for(String mode:List.of("STDIN","SQL","FUNCTION","STDIN")) {
            problems.update(number,languageDraft(mode),author);
            var detail=json.readTree(get("/api/problems/"+number,author).body()).get("data");
            var allowed=new HashSet<String>();detail.get("languages").forEach(v->allowed.add(v.asText()));
            assertEquals(new HashSet<>(Language.forMode(mode)),allowed);
        }
    }
    @Test void languageIsValidatedAndStoredByHttpApi() throws Exception {
        int stdin=publishedLanguageProblem("STDIN"),sql=publishedLanguageProblem("SQL"),function=publishedLanguageProblem("FUNCTION");
        for(Language language:Language.values()) {
            var response=submitLanguage(language==Language.SQL?sql:stdin,language.name(),"test source");
            assertEquals(200,response.statusCode(),response.body());
            long id=json.readTree(response.body()).get("data").asLong();
            assertEquals(language.name(),db.queryForObject("SELECT language FROM submissions WHERE id=?",String.class,id));
            assertEquals(language.name(),json.readTree(get("/api/submissions/"+id,other).body()).get("data").get("language").asText());
            // 이 테스트는 접수/저장만 확인합니다. 실행 테스트와 경쟁하지 않게 자신의 픽스처만 종료합니다.
            db.update("UPDATE submissions SET status='SYSTEM_ERROR' WHERE id=?",id);
        }
        for(String invalid:List.of("BASH","python","C;echo injected","../main",""))
            assertEquals(400,submitLanguage(stdin,invalid,"test").statusCode());
        assertEquals(400,submitLanguage(stdin,"SQL","SELECT 1").statusCode());
        assertEquals(400,submitLanguage(sql,"JAVA","test").statusCode());
        var functionCpp=submitLanguage(function,"CPP","test");
        assertEquals(200,functionCpp.statusCode(),functionCpp.body());
        db.update("UPDATE submissions SET status='SYSTEM_ERROR' WHERE id=?",
            json.readTree(functionCpp.body()).get("data").asLong());
        var legacy=submitLanguage(function,null,"def sol(matrix,target): return True");
        assertEquals(200,legacy.statusCode());
        long id=json.readTree(legacy.body()).get("data").asLong();
        assertEquals("PYTHON",db.queryForObject("SELECT language FROM submissions WHERE id=?",String.class,id));
        db.update("UPDATE submissions SET status='SYSTEM_ERROR' WHERE id=?",id);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="DOCKER_JUDGE_TEST",matches="true")
    void realWorkerHandlesCompiledSqlAndCompilationFailure() throws Exception {
        int stdin=publishedLanguageProblem("STDIN"),sql=publishedLanguageProblem("SQL");
        var worker=new JudgeWorker(submissions,dockerJudge);
        for(var sample:List.of(
                List.of("JAVA","import java.util.*;public class Main{public static void main(String[]a){Scanner s=new Scanner(System.in);System.out.println(s.nextInt()+s.nextInt());}}","ACCEPTED"),
                List.of("SQL","SELECT SUM(v) FROM numbers","ACCEPTED"),
                List.of("CPP","this is invalid C++ @@@","COMPILATION_ERROR"))) {
            var response=submitLanguage(sample.getFirst().equals("SQL")?sql:stdin,sample.getFirst(),sample.get(1));
            assertEquals(200,response.statusCode(),response.body());
            long id=json.readTree(response.body()).get("data").asLong();
            for(int attempt=0;attempt<20 && "QUEUED".equals(submissions.detail(id,other).get("status"));attempt++)worker.tick();
            var detail=json.readTree(get("/api/submissions/"+id,other).body()).get("data");
            assertEquals(sample.get(2),detail.get("status").asText(),detail.toString());
            assertEquals(sample.get(2),detail.get("cases").get(0).get("status").asText());
        }
    }
    private HttpResponse<String> signup(Map<String,Object> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/signup"))
            .header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void oversizedUserAgentCannotEraseFailedLoginAudit() throws Exception {
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/login"))
            .header("Content-Type","application/json").header("User-Agent","x".repeat(600))
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                Map.of("username",author.getUsername(),"password","wrong-password")))).build();
        assertEquals(401,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(1,db.queryForObject("SELECT login_failed_count FROM users WHERE id=?",Integer.class,author.getUserId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM users_log WHERE user_id=?",Integer.class,author.getUserId()));
    }
}
