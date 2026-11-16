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
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

/** V18과 실제 HTTP에서 공개 동의·저장·접근 권한·시험 시간창을 함께 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProblemAuthorVisibilityIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private CustomUserDetails author,other,admin;

    @BeforeEach void setup() {author=account(Role.USER,50);other=account(Role.USER,0);admin=account(Role.OPERATOR,0);}

    @Test void omittedCreateFlagAndDatabaseDefaultArePrivateWithoutLeakingIdentity() throws Exception {
        var body=payload("Private author "+unique(),null,null);
        int number=create(body);
        publish(number);
        assertPrivate(data(http("GET","/api/problems/"+number,null,null)),author.getUsername());
        assertFalse(data(http("GET","/api/problems/"+number+"/edit",null,author)).get("authorPublic").asBoolean());
        assertEquals("false",db.queryForObject("""
            SELECT COLUMN_DEFAULT FROM information_schema.columns
            WHERE table_schema=current_schema() AND table_name='problems' AND column_name='author_public'
            """,String.class));
        assertFalse(db.queryForObject("SELECT author_public FROM problems WHERE problem_number=?",Boolean.class,number));
    }

    @Test void publicAuthorUsesRealAccountNameAndCanBeHiddenAgain() throws Exception {
        var body=payload("Public author "+unique(),null,true);
        int number=create(body);publish(number);
        var visible=data(http("GET","/api/problems/"+number,null,null));
        assertPublic(visible,author.getUsername());
        assertTrue(data(http("GET","/api/problems/"+number+"/edit",null,author)).get("authorPublic").asBoolean());
        body.put("authorPublic",false);
        // 공개된 원본은 그대로 수정할 수 없습니다. 기존 운영자 비공개 전환/편집 절차를 지킵니다.
        assertEquals(409,http("PUT","/api/problems/"+number,body,author).statusCode());
        problems.setVisibility(number,new ProblemRequests.Visibility("PRIVATE","검증 편집"),admin);
        assertEquals(200,http("PUT","/api/problems/"+number,body,admin).statusCode());
        publish(number);
        // 본인/관리자도 공개 상세에서는 비공개 표시를 우회하지 않습니다.
        for(var viewer:new CustomUserDetails[]{null,author,admin})
            assertPrivate(data(http("GET","/api/problems/"+number,null,viewer)),author.getUsername());
    }

    @Test void oldAndNullUpdatePreserveChoiceButOtherUsersCannotChangeIt() throws Exception {
        var body=payload("Preserve author "+unique(),null,true);
        int number=create(body);
        body.remove("authorPublic");
        assertEquals(200,http("PUT","/api/problems/"+number,body,author).statusCode());
        assertTrue(data(http("GET","/api/problems/"+number+"/edit",null,author)).get("authorPublic").asBoolean());
        body.putNull("authorPublic");
        assertEquals(200,http("PUT","/api/problems/"+number,body,author).statusCode());
        publish(number);
        body.put("authorPublic",false);
        assertEquals(403,http("PUT","/api/problems/"+number,body,other).statusCode());
        assertEquals(401,http("PUT","/api/problems/"+number,body,null).statusCode());
        assertPublic(data(http("GET","/api/problems/"+number,null,null)),author.getUsername());
        assertEquals(403,http("GET","/api/problems/"+number+"/edit",null,other).statusCode());
    }

    @Test void groupAndContestKeepAuthorPrivacyAndExistingTimeAndMembershipGuards() throws Exception {
        long gid=groups.create(new GroupRequests.Write("av"+unique(),"제작자 표시 검증"),author);
        int hidden=create(payload("Hidden contest author "+unique(),gid,false));
        int visible=create(payload("Visible contest author "+unique(),gid,true));
        for(int number:new int[]{hidden,visible}) {
            problems.requestReview(number,author);
            problems.review(number,new ProblemRequests.Review("PUBLISHED","검증"),author);
        }
        assertEquals(404,http("GET","/api/problems/"+hidden,null,other).statusCode());
        groups.join(gid,other);
        assertPrivate(data(http("GET","/api/problems/"+hidden,null,other)),author.getUsername());
        assertPublic(data(http("GET","/api/problems/"+visible,null,other)),author.getUsername());
        long cid=contests.create(gid,new ContestRequests.Write("Author contest "+unique(),
            Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),List.of(hidden,visible),false,false,false),author);
        String path="/api/groups/"+gid+"/contests/"+cid+"/problems/";
        assertEquals(403,http("GET",path+visible,null,other).statusCode());
        assertEquals(401,http("GET",path+visible,null,null).statusCode());
        db.update("UPDATE group_contests SET starts_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 minute'),ends_at=((clock_timestamp() AT TIME ZONE 'UTC') + INTERVAL '1 hour') WHERE id=?",cid);
        assertPrivate(data(http("GET",path+hidden,null,other)),author.getUsername());
        assertPublic(data(http("GET",path+visible,null,other)),author.getUsername());
        // 시험에 사용된 문제의 일반 편집 금지를 제작자 표시 설정으로도 우회하지 않습니다.
        assertEquals(409,http("PUT","/api/problems/"+visible,payload("Visible contest author "+unique(),gid,false),author).statusCode());
        db.update("UPDATE group_contests SET ends_at=((clock_timestamp() AT TIME ZONE 'UTC') - INTERVAL '1 second') WHERE id=?",cid);
        assertEquals(403,http("GET",path+visible,null,other).statusCode());
    }

    private void assertPrivate(JsonNode detail,String username) {
        assertTrue(detail.get("authorPublic").isBoolean());assertFalse(detail.get("authorPublic").asBoolean());
        assertTrue(detail.has("authorUsername"));assertTrue(detail.get("authorUsername").isNull());
        assertFalse(detail.toString().contains(username));assertNoPrivateIdentity(detail);
    }
    private void assertPublic(JsonNode detail,String username) {
        assertTrue(detail.get("authorPublic").isBoolean());assertTrue(detail.get("authorPublic").asBoolean());
        assertEquals(username,detail.get("authorUsername").asText());assertNoPrivateIdentity(detail);
    }
    private static void assertNoPrivateIdentity(JsonNode detail) {
        for(String key:List.of("author_id","authorId","authorEmail","email","tests","referenceSolutions"))assertFalse(detail.has(key),key);
    }
    private void publish(int number) {problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","검증 공개"),admin);}
    private int create(ObjectNode body) throws Exception {var response=http("POST","/api/problems",body,author);return data(response).asInt();}
    private JsonNode data(HttpResponse<String> response) throws Exception {assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).get("data");}
    private ObjectNode payload(String title,Long gid,Boolean authorPublic) throws Exception {
        var draft=new ProblemRequests.Write(title,"본문","입력","출력","제한","ELITE",5,1000,128,"STDIN",
            List.of(new ProblemRequests.Example("1","1","예제")),List.of(new ProblemRequests.TestCase("1","1")),
            List.of("PYTHON"),List.of(),null,null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","answer.py","print(input())")),gid,gid==null?"PUBLIC":"GROUP",List.of());
        var result=(ObjectNode)json.readTree(json.writeValueAsString(draft));
        if(authorPublic==null) result.remove("authorPublic");else result.put("authorPublic",authorPublic);
        return result;
    }
    private CustomUserDetails account(Role role,int solved) {
        String username="a"+unique();
        db.update("INSERT INTO users(username,password,role,solved_count) VALUES(?,'author-test-only',?,?)",username,role.name(),solved);
        var entity=User.builder().username(username).password("author-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username));
        ReflectionTestUtils.setField(entity,"role",role);
        return new CustomUserDetails(entity);
    }
    private static String unique() {return UUID.randomUUID().toString().replace("-","").substring(0,12);}
    private HttpResponse<String> http(String method,String path,Object body,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(user!=null) request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        if(body!=null) request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
}
