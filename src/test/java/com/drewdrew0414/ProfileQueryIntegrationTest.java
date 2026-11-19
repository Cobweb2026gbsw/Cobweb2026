package com.drewdrew0414;

import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 전용 test DB에서 기존 이름을 변경하지 않고 공개 프로필의 query 별칭과 SPA 경로를 검사합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProfileQueryIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @Autowired PasswordEncoder encoder;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private HttpResponse<String> get(String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method,String path,String token,Map<String,String> body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).timeout(Duration.ofSeconds(5));
        if (token!=null) builder.header("Authorization","Bearer "+token);
        var content=body==null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        return client.send(builder.header("Content-Type","application/json").method(method,content).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void renamingKeepsTheSameSessionLoginAndCommunityAuthorAfterOldNameIsReused() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String oldName="old"+suffix,newName="새"+suffix,password="profile-test-password";
        long id=db.queryForObject("INSERT INTO users(username,password) VALUES(?,?) RETURNING id",
                Long.class,oldName,encoder.encode(password));
        var login=request("POST","/api/auth/login",null,Map.of("username",oldName,"password",password));
        assertEquals(200,login.statusCode());
        String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        String token=json.readTree(login.body()).get("data").get("accessToken").asText();
        long post=db.queryForObject("INSERT INTO community_posts(category,title,body,author_id,author_username) VALUES('GENERAL','글','내용',?,?) RETURNING id",
                Long.class,id,oldName);
        db.update("INSERT INTO community_comments(post_id,body,author_id,author_username) VALUES(?,'댓글',?,?)",post,id,oldName);

        assertEquals(200,request("PATCH","/api/me",token,Map.of("username",newName,"bio","소개","githubUrl","")).statusCode());
        assertEquals(404,get("/api/profiles/"+oldName).statusCode());
        db.update("INSERT INTO users(username,password) VALUES(?,'unrelated-account')",oldName);

        assertEquals(401,request("GET","/api/me",token,null).statusCode());
        var reissue=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/reissue"))
                .header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,reissue.statusCode());
        String fresh=json.readTree(reissue.body()).get("data").get("accessToken").asText();
        var me=json.readTree(request("GET","/api/me",fresh,null).body()).get("data");
        assertEquals(200,request("POST","/api/auth/login",null,Map.of("username",newName,"password",password)).statusCode());
        assertEquals(Long.toString(id),me.get("id").asText());
        assertEquals(newName,me.get("username").asText());
        var article=json.readTree(get("/api/community/posts/"+post).body()).get("data");
        assertEquals(newName,article.get("authorUsername").asText());
        assertEquals(newName,article.get("comments").get(0).get("authorUsername").asText());
        assertEquals(oldName,db.queryForObject("SELECT author_username FROM community_posts WHERE id=?",String.class,post));
    }

    @Test void rejectedRenameIsAtomicAndOlderProfileFormsCanStillUpdateBio() throws Exception {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        String name="me"+suffix,taken="taken"+suffix;
        long id=db.queryForObject("INSERT INTO users(username,password) VALUES(?,'unused') RETURNING id",Long.class,name);
        db.update("INSERT INTO users(username,password) VALUES(?,'unused')",taken);
        String token=jwt.generateAccessToken(id,name,"USER",0);
        var duplicate=request("PATCH","/api/me",token,Map.of("username",taken.toUpperCase(java.util.Locale.ROOT),"bio","미저장","githubUrl",""));
        assertEquals(409,duplicate.statusCode());
        assertEquals("DUPLICATE_USERNAME",json.readTree(duplicate.body()).get("errorCode").asText());
        for(String invalid: new String[]{"   ","X".repeat(17),"a\nb"}) {
            assertEquals(400,request("PATCH","/api/me",token,Map.of("username",invalid,"bio","미저장","githubUrl","")).statusCode());
        }
        assertEquals("",db.queryForObject("SELECT bio FROM users WHERE id=?",String.class,id));
        assertEquals(200,request("PATCH","/api/me",token,Map.of("bio","저장","githubUrl","")).statusCode());
        assertEquals(name,db.queryForObject("SELECT username FROM users WHERE id=?",String.class,id));
        assertEquals("저장",db.queryForObject("SELECT bio FROM users WHERE id=?",String.class,id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"..", "a/b", "기존사용자"})
    void queryAliasPreservesExistingDatabaseNamesAndOnlyReturnsPublicFields(String username) throws Exception {
        // 재실행 시 같은 이름이 있으면 그 사용자 행을 변경하지 않습니다. 테스트 콘텐츠도 삭제하지 않습니다.
        db.update("INSERT INTO users(username,password,role) VALUES(?,'profile-query-test-only','USER') ON CONFLICT DO NOTHING", username);
        String query = "?username=" + URLEncoder.encode(username, StandardCharsets.UTF_8);

        var response = get("/api/profiles" + query);
        assertEquals(200, response.statusCode(), username);
        var envelope = json.readTree(response.body());
        assertTrue(envelope.get("success").asBoolean());
        var profile = envelope.get("data");
        assertEquals(username, profile.get("username").asText());
        assertTrue(profile.get("solvedProblems").isArray());
        for (String privateField : new String[]{"id", "email", "password", "role", "provider_id", "accessToken"})
            assertFalse(profile.has(privateField), privateField);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?", Integer.class, username));

        var page = get("/users" + query);
        assertEquals(200, page.statusCode(), "익명 SPA 접근: " + username);
        assertTrue(page.body().contains("id=\"root\""));
        assertEquals(get("/ui/index.html").body(), page.body());
    }

    @Test
    void legacySingleSegmentEndpointAndPageRemainCompatible() throws Exception {
        String username = "p" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'profile-query-test-only','USER')", username);
        var legacy = get("/api/profiles/" + username);
        var alias = get("/api/profiles?username=" + username);
        assertEquals(200, legacy.statusCode());
        assertEquals(200, alias.statusCode());
        assertEquals(json.readTree(legacy.body()).get("data"), json.readTree(alias.body()).get("data"));
        assertEquals(200, get("/users/" + username).statusCode());
    }
}
