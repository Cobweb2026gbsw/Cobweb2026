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
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @Autowired com.drewdrew0414.domain.user.service.EmailVerificationService emailVerification;
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
            List.of(new ProblemRequests.TestCase("{\"matrix\":[],\"target\":[7,7]}","true")));
    }
    HttpResponse<String> get(String path,CustomUserDetails user)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(user!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name()));
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
            .header("Authorization","Bearer "+jwt.generateAccessToken(other.getUserId(),other.getUsername(),"USER"))
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
            .header("Authorization","Bearer "+jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER"))
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
        String token=jwt.generateAccessToken(other.getUserId(),author.getUsername(),"USER");
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",token).statusCode()));
    }
    @Test void missingAccountTokenDoesNotBreakPublicRequests() throws Exception {
        String token=jwt.generateAccessToken(author.getUserId(),"missing_"+UUID.randomUUID(),"USER");
        assertEquals(200,getWithToken("/api/problems",token).statusCode());
        assertTrue(Set.of(401,403).contains(getWithToken("/api/me",token).statusCode()));
    }
    HttpResponse<String> getWithToken(String path,String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void refreshTokensAreUniqueEvenWhenIssuedImmediately(){
        var tokens=new HashSet<String>();
        for(int i=0;i<20;i++)assertTrue(tokens.add(jwt.generateRefreshToken(author.getUserId())));
    }
    @Test void failedLoginIsRecordedDespiteAuthenticationException(){
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        assertThrows(com.drewdrew0414.domain.user.exception.PasswordMismatchException.class,()->
            auth.login(author.getUsername(),"wrong-password","127.0.0.1","test",new org.springframework.mock.web.MockHttpServletResponse()));
        assertEquals(1,db.queryForObject("SELECT login_failed_count FROM users WHERE id=?",Integer.class,author.getUserId()));
    }
    @Test void stoppedAccountCannotRefreshTokens(){
        String token=jwt.generateRefreshToken(author.getUserId());
        db.update("INSERT INTO refresh_token(user_id,token,expires_at) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 1 DAY))",author.getUserId(),token);
        db.update("UPDATE users SET status='STOPPED' WHERE id=?",author.getUserId());
        assertThrows(com.drewdrew0414.domain.user.exception.BannedUserException.class,()->
            auth.reissue(token,new org.springframework.mock.web.MockHttpServletResponse()));
    }
    @Test void nullTestCaseIsBadRequestInsteadOfServerError() throws Exception {
        var body=json.readTree(json.writeValueAsString(draft()));
        ((tools.jackson.databind.node.ObjectNode)body).putArray("tests").addNull();
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/problems"))
            .header("Content-Type","application/json")
            .header("Authorization","Bearer "+jwt.generateAccessToken(author.getUserId(),author.getUsername(),"USER"))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        assertEquals(400,HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void invalidNumericParametersReturnBadRequest() throws Exception {
        assertEquals(400,get("/api/problems/not-a-number",null).statusCode());
        assertEquals(400,get("/api/ranking?page=abc",null).statusCode());
    }
    @Test void refreshRotationInvalidatesPreviousToken(){
        db.update("UPDATE users SET password=? WHERE id=?",encoder.encode("correct-password"),author.getUserId());
        auth.login(author.getUsername(),"correct-password","127.0.0.1","test",new org.springframework.mock.web.MockHttpServletResponse());
        String previous=db.queryForObject("SELECT token FROM refresh_token WHERE user_id=?",String.class,author.getUserId());
        auth.reissue(previous,new org.springframework.mock.web.MockHttpServletResponse());
        String current=db.queryForObject("SELECT token FROM refresh_token WHERE user_id=?",String.class,author.getUserId());
        assertNotEquals(previous,current);
        assertThrows(com.drewdrew0414.domain.user.exception.InvalidTokenException.class,()->
            auth.reissue(previous,new org.springframework.mock.web.MockHttpServletResponse()));
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
        db.update("INSERT INTO email_verifications(email,code,purpose,expires_at) VALUES(?,'83920174','JOIN',?)",
                email,java.time.LocalDateTime.now().plusMinutes(5));
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
