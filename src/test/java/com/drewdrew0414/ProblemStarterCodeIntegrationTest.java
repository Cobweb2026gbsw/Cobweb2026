package com.drewdrew0414;

import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 전용 MySQL에서 V15, 공개/비공개 분리, 생략 보존 및 코테 시간창을 실제 HTTP로 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProblemStarterCodeIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private CustomUserDetails owner,member,admin;
    private static final String STARTER="# PUBLIC_STARTER\ndef sol(x: int, y: int):\n    pass\n";
    private static final String PRIVATE_SOURCE="# PRIVATE_REFERENCE_SENTINEL\nprint(input())\n";

    @BeforeEach void setup() {owner=user(Role.USER,50);member=user(Role.USER,0);admin=user(Role.OPERATOR,0);}

    @Test void httpCreationAndPublicationExposeOnlyExplicitStarterCodesNotReferenceSolutions() throws Exception {
        var request=draft("Public starter "+unique(),null,"PUBLIC",List.of(new ProblemRequests.StarterCode("PYTHON",STARTER)));
        var created=http("POST","/api/problems",request,owner);
        assertEquals(200,created.statusCode(),created.body());
        int number=json.readTree(created.body()).get("data").asInt();
        assertEquals(404,http("GET","/api/problems/"+number,null,null).statusCode());
        var editor=json.readTree(http("GET","/api/problems/"+number+"/edit",null,owner).body()).get("data");
        assertEquals(STARTER,editor.get("starterCodes").get(0).get("sourceCode").asText());
        assertEquals(PRIVATE_SOURCE,editor.get("referenceSolutions").get(0).get("sourceCode").asText());

        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","publish"),admin);
        var response=http("GET","/api/problems/"+number,null,null);
        assertEquals(200,response.statusCode());
        var detail=json.readTree(response.body()).get("data");
        assertTrue(detail.get("functionAbi").isNull());
        assertEquals(STARTER,detail.get("starterCodes").get(0).get("sourceCode").asText());
        assertFalse(detail.has("tests"));assertFalse(detail.has("referenceSolutions"));
        assertFalse(response.body().contains("PRIVATE_REFERENCE_SENTINEL"));
        assertEquals(403,http("GET","/api/problems/"+number+"/edit",null,member).statusCode());
    }

    @Test void oldJsonOmissionPreservesStartersAndExplicitEmptyListClearsThem() throws Exception {
        String title="Preserve starter "+unique();
        var request=draft(title,null,"PUBLIC",List.of(new ProblemRequests.StarterCode("PYTHON",STARTER)));
        int number=problems.create(request,owner);
        var oldPayload=(ObjectNode)json.readTree(json.writeValueAsString(request));
        oldPayload.remove("starterCodes");
        assertEquals(200,http("PUT","/api/problems/"+number,oldPayload,owner).statusCode());
        assertEquals(STARTER,((List<?>)problems.edit(number,owner).get("starterCodes")).stream()
            .map(row->((java.util.Map<?,?>)row).get("sourceCode")).findFirst().orElseThrow());
        var clear=draft(title,null,"PUBLIC",List.of());
        assertEquals(200,http("PUT","/api/problems/"+number,clear,owner).statusCode());
        assertEquals(List.of(),problems.edit(number,owner).get("starterCodes"));
        assertEquals(1,((List<?>)problems.edit(number,owner).get("referenceSolutions")).size());
    }

    @Test void httpRejectsDuplicateForbiddenAndOversizedUtf8StartersWithoutCreatingProblemRows() throws Exception {
        for(var starters:List.of(
            List.of(new ProblemRequests.StarterCode("JAVA",STARTER)),
            List.of(new ProblemRequests.StarterCode("PYTHON",STARTER),new ProblemRequests.StarterCode("PYTHON",STARTER)),
            List.of(new ProblemRequests.StarterCode("PYTHON","한".repeat(21846))))) {
            String title="Invalid starter "+unique();
            var response=http("POST","/api/problems",draft(title,null,"PUBLIC",starters),owner);
            assertEquals(400,response.statusCode(),response.body());
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_title=?",Integer.class,title));
        }
    }

    @Test void groupAndContestStarterMetadataObeyTheExistingMembershipAndTimeGuards() throws Exception {
        // study_groups.group_name은 16자 제한이므로 접두사와 고유값의 합을 15자로 유지합니다.
        long gid=groups.create(new GroupRequests.Write("sg"+unique(),"전용 테스트"),owner);
        int number=problems.create(draft("Contest starter "+unique(),gid,"GROUP",
            List.of(new ProblemRequests.StarterCode("PYTHON",STARTER))),owner);
        problems.requestReview(number,owner);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","review"),owner);
        assertEquals(404,http("GET","/api/problems/"+number,null,member).statusCode());
        groups.join(gid,member);
        assertEquals(STARTER,json.readTree(http("GET","/api/problems/"+number,null,member).body())
            .get("data").get("starterCodes").get(0).get("sourceCode").asText());
        long cid=contests.create(gid,new ContestRequests.Write("Starter contest "+unique(),
            Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),List.of(number),false,false,false),owner);
        String endpoint="/api/groups/"+gid+"/contests/"+cid+"/problems/"+number;
        assertEquals(403,http("GET",endpoint,null,member).statusCode());
        assertEquals(404,http("GET","/api/problems/"+number,null,member).statusCode());
        db.update("UPDATE group_contests SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?",cid);
        var response=http("GET",endpoint,null,member);
        assertEquals(200,response.statusCode(),response.body());
        assertTrue(json.readTree(response.body()).get("data").get("functionAbi").isNull());
        assertEquals(STARTER,json.readTree(response.body()).get("data").get("starterCodes").get(0).get("sourceCode").asText());
        assertFalse(response.body().contains("PRIVATE_REFERENCE_SENTINEL"));
        db.update("UPDATE group_contests SET ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",cid);
        assertEquals(403,http("GET",endpoint,null,member).statusCode());
        var error=assertThrows(ResponseStatusException.class,()->contests.problem(gid,cid,number,member));
        assertEquals(403,error.getStatusCode().value());
    }

    private static ProblemRequests.Write draft(String title,Long groupId,String visibility,List<ProblemRequests.StarterCode> starters) {
        return new ProblemRequests.Write(title,"본문","입력","출력","제한","INITIATE",1,1000,128,
            "STDIN",List.of(new ProblemRequests.Example("1","1","예제")),List.of(new ProblemRequests.TestCase("1","1")),
            List.of("PYTHON"),List.of(),null,null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","answer.py",PRIVATE_SOURCE)),groupId,visibility,starters);
    }
    private CustomUserDetails user(Role role,int solved) {
        String name="s"+unique();
        db.update("INSERT INTO users(username,password,role,solved_count) VALUES(?,'starter-test-only',?,?)",name,role.name(),solved);
        var entity=User.builder().username(name).password("starter-test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",role);
        return new CustomUserDetails(entity);
    }
    private static String unique() {return UUID.randomUUID().toString().replace("-","").substring(0,13);}
    private HttpResponse<String> http(String method,String path,Object payload,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if(user!=null) request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        if(payload!=null) request.header("Content-Type","application/json");
        var publisher=payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload));
        return client.send(request.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
    }
}
