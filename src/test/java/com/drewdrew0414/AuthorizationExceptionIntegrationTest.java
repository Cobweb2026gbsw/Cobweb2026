package com.drewdrew0414;

import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** 권한 행렬을 실제 HTTP/JWT/DB에서 검사하며 오류 응답의 안정적인 코드를 함께 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthorizationExceptionIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private CustomUserDetails author,other,operator,developer;

    @BeforeEach void setup() {
        author=account(Role.USER,50);other=account(Role.USER,0);
        operator=account(Role.OPERATOR,0);developer=account(Role.DEVELOPER,0);
    }

    @Test void publicPrivateDraftAndArchivedProblemAccessFollowTheRoleAndOwnershipMatrix() throws Exception {
        int published=problems.create(draft("Public access "+unique(),null),author);
        publish(published);
        int hidden=problems.create(draft("Private access "+unique(),null),author);
        publish(hidden);
        problems.setVisibility(hidden,new ProblemRequests.Visibility("PRIVATE","권한 검증"),operator);
        int pending=problems.create(draft("Draft access "+unique(),null),author);
        int archived=problems.create(draft("Archived access "+unique(),null),author);
        publish(archived);
        problems.review(archived,new ProblemRequests.Review("ARCHIVED","권한 검증"),operator);

        for(CustomUserDetails viewer:new CustomUserDetails[]{null,other,author,operator,developer})
            success(http("GET","/api/problems/"+published,null,viewer));
        for(int number:new int[]{hidden,pending,archived}) {
            for(CustomUserDetails viewer:List.of(author,operator,developer)) {
                var detail=success(http("GET","/api/problems/"+number,null,viewer));
                assertEquals(number,detail.get("problem_number").asInt());
                assertFalse(detail.has("tests"));assertFalse(detail.has("referenceSolutions"));
                assertTrue(detail.get("authorUsername").isNull());
            }
            for(CustomUserDetails viewer:new CustomUserDetails[]{null,other})
                failure(http("GET","/api/problems/"+number,null,viewer),404,"RESOURCE_NOT_FOUND");
            failure(http("GET","/api/problems/"+number+"/edit",null,other),404,"RESOURCE_NOT_FOUND");
            for(CustomUserDetails viewer:List.of(author,operator,developer))
                assertTrue(success(http("GET","/api/problems/"+number+"/edit",null,viewer)).has("tests"));
        }
        failure(http("GET","/api/problems/"+published+"/edit",null,other),403,"PERMISSION_DENIED");
        failure(http("GET","/api/problems/"+published+"/edit",null,null),401,"AUTHENTICATION_REQUIRED");
        failure(http("GET","/api/admin/problems",null,other),403,"PERMISSION_DENIED");
        for(CustomUserDetails viewer:List.of(operator,developer)) success(http("GET","/api/admin/problems",null,viewer));
    }

    @Test void unapprovedGroupStatusRevokesResidualMemberAccessButKeepsPlatformInspection() throws Exception {
        long gid=groups.create(new GroupRequests.Write("az"+unique(),"미승인 그룹 권한 검증"),author);
        groups.join(gid,other);
        int number=problems.create(draft("Group access "+unique(),gid),author);
        problems.requestReview(number,author);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","권한 검증"),author);
        long cid=contests.create(gid,new ContestRequests.Write("Access contest "+unique(),
            Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),List.of(number),false,false,false),author);
        db.update("UPDATE group_contests SET starts_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 minute'),ends_at=((clock_timestamp() AT TIME ZONE 'UTC') + INTERVAL '1 hour') WHERE id=?",cid);
        String groupPath="/api/groups/"+gid;
        String examPath=groupPath+"/contests/"+cid;
        String questionPath=examPath+"/problems/"+number;
        var submission=Map.of("problemNumber",number,"language","PYTHON","sourceCode","print(input())");
        success(http("GET",groupPath,null,other));
        success(http("GET",groupPath+"/roles",null,other));
        success(http("GET",questionPath,null,other));
        long before=db.queryForObject("SELECT COUNT(*) FROM submissions WHERE contest_id=?",Long.class,cid);
        try {
            for(String state:List.of("WAITING","REJECTED")) {
                db.update("UPDATE study_groups SET status=? WHERE id=?",state,gid);
                failure(http("GET",groupPath,null,other),403,"PERMISSION_DENIED");
                failure(http("GET",groupPath+"/roles",null,other),403,"PERMISSION_DENIED");
                failure(http("GET",questionPath,null,other),403,"PERMISSION_DENIED");
                failure(http("POST",examPath+"/submissions",submission,other),403,"PERMISSION_DENIED");
                failure(http("GET","/api/problems/"+number,null,other),404,"RESOURCE_NOT_FOUND");
                for(CustomUserDetails viewer:List.of(operator,developer)) {
                    assertEquals(state,success(http("GET",groupPath,null,viewer)).get("group").get("status").asText());
                    success(http("GET",groupPath+"/roles",null,viewer));
                    success(http("GET",questionPath,null,viewer));
                    // 관리 권한은 시험 응시 권한을 대신하지 않습니다.
                    failure(http("POST",examPath+"/submissions",submission,viewer),403,"PERMISSION_DENIED");
                }
            }
        } finally {
            db.update("UPDATE study_groups SET status='ACCEPTED' WHERE id=?",gid);
        }
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM submissions WHERE contest_id=?",Long.class,cid));
        success(http("GET",questionPath,null,other));
        groups.removeMember(gid,other.getUserId(),author);
        failure(http("GET",groupPath,null,other),403,"PERMISSION_DENIED");
        failure(http("GET",questionPath,null,other),403,"PERMISSION_DENIED");
        failure(http("POST",examPath+"/submissions",submission,other),403,"PERMISSION_DENIED");
    }

    @Test void existingTokenCannotKeepDemotedOrDisabledAdministratorPrivileges() throws Exception {
        int number=problems.create(draft("Token access "+unique(),null),author);
        String token=token(operator);
        success(httpToken("GET","/api/problems/"+number,null,token));
        db.update("UPDATE users SET role='USER' WHERE id=?",operator.getUserId());
        failure(httpToken("GET","/api/admin/problems",null,token),403,"PERMISSION_DENIED");
        failure(httpToken("GET","/api/problems/"+number,null,token),404,"RESOURCE_NOT_FOUND");
        for(String state:List.of("TIMEOUT","STOPPED","BANNED")) {
            db.update("UPDATE users SET role='OPERATOR',status=? WHERE id=?",state,operator.getUserId());
            failure(httpToken("GET","/api/admin/problems",null,token),401,"AUTHENTICATION_REQUIRED");
            failure(httpToken("GET","/api/problems/"+number,null,token),404,"RESOURCE_NOT_FOUND");
            failure(httpToken("GET","/api/problems/"+number+"/edit",null,token),401,"AUTHENTICATION_REQUIRED");
        }
        db.update("UPDATE users SET role='OPERATOR',status='ACTIVE' WHERE id=?",operator.getUserId());
        success(httpToken("GET","/api/problems/"+number,null,token));
    }

    private JsonNode success(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),response.body());
        var body=json.readTree(response.body());assertTrue(body.get("success").asBoolean());
        return body.get("data");
    }
    private void failure(HttpResponse<String> response,int status,String code) throws Exception {
        assertEquals(status,response.statusCode(),response.body());
        var body=json.readTree(response.body());assertFalse(body.get("success").asBoolean());
        assertTrue(body.get("data").isNull());assertEquals(code,body.get("errorCode").asText());
        assertNotNull(body.get("message"));assertFalse(body.get("message").asText().isBlank());
        for(String secret:List.of("print(input())","authorization-test-only","SELECT ","referenceSolutions","sourceCode","stackTrace"))
            assertFalse(response.body().contains(secret));
    }
    private void publish(int number) {problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","권한 검증"),operator);}
    private static ProblemRequests.Write draft(String title,Long gid) {
        return new ProblemRequests.Write(title,"권한 경계 검증 본문","입력","출력","제한","ELITE",5,1000,128,"STDIN",
            List.of(new ProblemRequests.Example("1","1","예제")),List.of(new ProblemRequests.TestCase("1","1")),List.of("PYTHON"),
            List.of(),null,null,List.of(new ProblemRequests.ReferenceSolution("PYTHON","answer.py","print(input())")),gid,gid==null?"PUBLIC":"GROUP",List.of());
    }
    private CustomUserDetails account(Role role,int solved) {
        String username="z"+unique();
        db.update("INSERT INTO users(username,password,role,solved_count) VALUES(?,'authorization-test-only',?,?)",username,role.name(),solved);
        var entity=User.builder().username(username).password("authorization-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username));
        ReflectionTestUtils.setField(entity,"role",role);
        return new CustomUserDetails(entity);
    }
    private String token(CustomUserDetails user) {return jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0);}
    private static String unique() {return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    private HttpResponse<String> http(String method,String path,Object payload,CustomUserDetails user) throws Exception {
        return httpToken(method,path,payload,user==null?null:token(user));
    }
    private HttpResponse<String> httpToken(String method,String path,Object payload,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(payload!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
