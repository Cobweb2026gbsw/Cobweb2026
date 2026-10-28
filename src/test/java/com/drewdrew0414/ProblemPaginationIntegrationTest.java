package com.drewdrew0414;

import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 전용 test DB의 구분된 fixture를 실제 HTTP로 읽고 기존 배열 API와 새 메타데이터를 함께 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProblemPaginationIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newHttpClient();
    private int nextNumber;

    @Test void numberedPagesUseTheSameFilteredVisibilityAndAccountScopeAsTheirItems() throws Exception {
        assertTrue(db.queryForObject("SELECT current_database()",String.class).endsWith("_test"),"Only a dedicated test database is allowed");
        var owner=account();var other=account();
        String prefix="pagination"+UUID.randomUUID().toString().replace("-","");
        nextNumber=db.queryForObject("SELECT COALESCE(MAX(problem_number),999)+1001 FROM problems",Integer.class);
        var publicIds=new ArrayList<Long>();
        for(int index=0;index<64;index++) {
            String rank=index<31?"ELITE":index<62?"EXPERT":index==62?"ELITE":"EXPERT";
            int level=index<31||index==63?1:5;
            publicIds.add(problem(prefix+(index<60?"-exact-":"-rest-")+index,rank,level,"PUBLISHED","PUBLIC",owner,null));
        }
        problem(prefix+"-draft","ELITE",1,"DRAFT","PRIVATE",owner,null);
        problem(prefix+"-private","ELITE",1,"PUBLISHED","PRIVATE",owner,null);
        problem(prefix+"-archived","ELITE",1,"ARCHIVED","PUBLIC",owner,null);
        String groupName="pg"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO study_groups(group_name,status) VALUES(?,'ACCEPTED')",groupName);
        long group=db.queryForObject("SELECT id FROM study_groups WHERE group_name=?",Long.class,groupName);
        problem(prefix+"-group","ELITE",1,"PUBLISHED","GROUP",owner,group);
        problem(prefix+"-other-private","ELITE",1,"DRAFT","PRIVATE",other,null);
        for(long id:publicIds.subList(0,2))solved(owner,id);
        for(long id:publicIds.subList(0,3))solved(other,id);
        String query="?search="+URLEncoder.encode(prefix,StandardCharsets.UTF_8);

        var legacy=data("/api/problems"+query,null);
        assertTrue(legacy.isArray());assertEquals(30,legacy.size());
        assertTrue(data("/api/problems"+query+"&pagination=false",null).isArray());
        var first=data("/api/problems"+query+"&pagination=true",null);
        page(first,0,64,3,30);
        assertEquals(Set.of("items","page","pageSize","totalItems","totalPages"),fieldNames(first));
        for(var item:first.get("items")) {
            assertFalse(item.get("solved").asBoolean());
            for(String privateKey:List.of("sourceCode","tests","referenceSolutions","author_id","authorId"))
                assertFalse(item.has(privateKey));
        }
        page(data("/api/problems"+query+"&pagination=true&page=2",null),2,64,3,4);
        page(data("/api/problems"+query+"&pagination=true&page=2147483647",null),2,64,3,4);
        page(data("/api/problems"+query+"&pagination=true&page=-99",null),0,64,3,30);
        page(data("/api/problems?pagination=true&search="+prefix+"-missing&page=99",null),0,0,0,0);
        page(data("/api/problems?pagination=true&search="+prefix+"-exact-&page=1",null),1,60,2,30);
        // 30건을 꽉 채운 마지막 페이지에서도 존재하지 않는 다음 페이지가 생기지 않습니다.
        page(data("/api/problems?pagination=true&search="+prefix+"-exact-&page=2",null),1,60,2,30);

        String selected=query+"&pagination=true&difficulty=ELITE%3A1&difficulty=EXPERT%3A5";
        page(data("/api/problems"+selected,owner),0,62,3,30);
        page(data("/api/problems"+selected+"&solved=solved",owner),0,2,1,2);
        page(data("/api/problems"+selected+"&solved=solved",other),0,3,1,3);
        page(data("/api/problems"+selected+"&solved=unsolved",owner),0,60,2,30);
        page(data("/api/problems"+selected+"&solved=unsolved&page=1",owner),1,60,2,30);
        assertEquals(401,http("/api/problems"+selected+"&solved=solved",null).statusCode());
        assertEquals(400,http("/api/problems"+query+"&pagination=true&difficulty=ELITE%3A6",owner).statusCode());
        assertEquals(400,http("/api/problems"+query+"&pagination=true&solved=INVALID",owner).statusCode());
        assertEquals(400,http("/api/problems"+query+"&pagination=invalid",owner).statusCode());
        assertEquals(400,http("/api/problems"+query+"&pagination=true&page=not-a-number",owner).statusCode());

        page(data("/api/problems/mine"+query+"&pagination=true",owner),0,68,3,30);
        page(data("/api/problems/mine"+query+"&pagination=true",other),0,1,1,1);
        page(data("/api/problems/mine"+selected+"&solved=solved",owner),0,2,1,2);
        assertTrue(data("/api/problems/mine"+query,owner).isArray());
        assertEquals(401,http("/api/problems/mine"+query+"&pagination=true",null).statusCode());
    }

    private long problem(String title,String rank,int level,String status,String visibility,CustomUserDetails author,Long group) {
        int number=nextNumber++;
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
                rank_type,rank_int,status,visibility,author_id,group_id)
            VALUES(?,?,'pagination test body','','',?,?,?,?,?,?)
            """,number,title,rank,level,status,visibility,author.getUserId(),group);
        return db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
    }
    private void solved(CustomUserDetails account,long problem) {
        db.update("INSERT INTO user_solved_problems(user_id,problem_id) VALUES(?,?)",account.getUserId(),problem);
    }
    private CustomUserDetails account() {
        String name="pg"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'pagination-test-only','USER')",name);
        var entity=User.builder().username(name).password("pagination-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",Role.USER);
        return new CustomUserDetails(entity);
    }
    private HttpResponse<String> http(String path,CustomUserDetails account) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(account!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(
            account.getUserId(),account.getUsername(),account.getUser().getRole().name(),0));
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(String path,CustomUserDetails account) throws Exception {
        var response=http(path,account);assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).get("data");
    }
    private static void page(JsonNode page,int expectedPage,long total,long pages,int items) {
        assertTrue(page.isObject());assertEquals(expectedPage,page.get("page").asInt());assertEquals(30,page.get("pageSize").asInt());
        assertEquals(total,page.get("totalItems").asLong());assertEquals(pages,page.get("totalPages").asLong());
        assertTrue(page.get("items").isArray());assertEquals(items,page.get("items").size());
    }
    private static Set<String> fieldNames(JsonNode value) {
        return value.properties().stream().map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }
}
