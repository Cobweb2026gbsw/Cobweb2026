package com.drewdrew0414;

import com.drewdrew0414.domain.problem.*;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 전용 test DB와 실제 HTTP에서 계정별 해결 여부·정확한 난이도 쌍·필터 후 페이징을 함께 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProblemListIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @Test void publicAndMineListsKeepAccountPrivacyExactPairsAndFilteringBeforeThePageLimit() throws Exception {
        var author=account(Role.USER);
        var other=account(Role.USER);
        var operator=account(Role.OPERATOR);
        String prefix="list"+UUID.randomUUID().toString().substring(0,12);
        int eliteOne=publish(prefix+"-elite1","ELITE",1,author,operator);
        int expertFive=publish(prefix+"-expert5","EXPERT",5,author,operator);
        int eliteFive=publish(prefix+"-elite5","ELITE",5,author,operator);
        int expertOne=publish(prefix+"-expert1","EXPERT",1,author,operator);
        int privateNumber=problems.create(draft(prefix+"-private","ELITE",2),author);
        solved(author,eliteOne);solved(other,expertFive);
        String search="?search="+URLEncoder.encode(prefix,StandardCharsets.UTF_8);

        var anonymous=data("/api/problems"+search,null);
        assertEquals(Set.of(eliteOne,expertFive,eliteFive,expertOne),numbers(anonymous));
        for(var item:anonymous) { assertTrue(item.get("solved").isBoolean());assertFalse(item.get("solved").asBoolean()); }
        var firstAccount=data("/api/problems"+search,author);
        assertEquals(Set.of(eliteOne),solvedNumbers(firstAccount));
        assertEquals(Set.of(expertFive),solvedNumbers(data("/api/problems"+search,other)));
        assertEquals(Set.of(),solvedNumbers(data("/api/problems"+search,null)),"로그아웃 조회에 이전 계정 해결 상태가 남지 않습니다.");
        assertFalse(numbers(firstAccount).contains(privateNumber));

        String pairs="&difficulty=ELITE%3A1&difficulty=EXPERT%3A5";
        assertEquals(Set.of(eliteOne,expertFive),numbers(data("/api/problems"+search+pairs,author)));
        assertEquals(Set.of(eliteOne),numbers(data("/api/problems"+search+pairs+"&solved=solved",author)));
        assertEquals(Set.of(expertFive),numbers(data("/api/problems"+search+pairs+"&solved=unsolved",author)));
        assertEquals(Set.of(expertFive,eliteFive,expertOne),numbers(data("/api/problems"+search+"&solved=unsolved",author)));
        assertEquals(401,http("/api/problems"+search+"&solved=solved",null).statusCode());
        assertEquals(401,http("/api/problems"+search+"&solved=unsolved",null).statusCode());
        assertEquals(400,http("/api/problems"+search+"&difficulty=ELITE%3A6",author).statusCode());
        assertEquals(400,http("/api/problems"+search+"&solved=SOLVED",author).statusCode());
        assertEquals(Set.of(eliteOne),numbers(data("/api/problems/mine"+search+"&solved=solved",author)));
        assertEquals(5,data("/api/problems/mine"+search,author).size());
        assertEquals(0,data("/api/problems/mine"+search,other).size(),"다른 계정의 작성 문제는 내 목록에 포함하지 않습니다.");
        assertEquals(401,http("/api/problems/mine"+search,null).statusCode());

        String pagePrefix=prefix+"-pages";
        var lastTwo=new HashSet<Integer>();
        for(int index=0;index<32;index++) {
            boolean selected=index>=30;
            int number=publish(pagePrefix+"-"+index,selected?"EXPERT":"INITIATE",selected?5:1,author,operator);
            if(selected) { solved(author,number);lastTwo.add(number); }
        }
        String pageSearch="?search="+URLEncoder.encode(pagePrefix,StandardCharsets.UTF_8);
        assertEquals(30,data("/api/problems"+pageSearch+"&page=0",author).size());
        assertEquals(lastTwo,numbers(data("/api/problems"+pageSearch+"&page=1",author)));
        assertEquals(lastTwo,numbers(data("/api/problems"+pageSearch+"&difficulty=EXPERT%3A5&solved=solved&page=0",author)),
            "원래 두 번째 페이지에 있던 일치 문제도 DB 필터 후 첫 페이지에서 반환해야 합니다.");
        assertEquals(0,data("/api/problems"+pageSearch+"&difficulty=EXPERT%3A5&solved=solved&page=1",author).size());
    }

    private JsonNode data(String path,CustomUserDetails account) throws Exception {
        var response=http(path,account);
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body()).get("data");
    }
    private HttpResponse<String> http(String path,CustomUserDetails account) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(account!=null)request.header("Authorization","Bearer "+jwt.generateAccessToken(
            account.getUserId(),account.getUsername(),account.getUser().getRole().name(),0));
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private int publish(String title,String rank,int level,CustomUserDetails author,CustomUserDetails operator) {
        int number=problems.create(draft(title,rank,level),author);
        problems.requestReview(number,author);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","목록 검증"),operator);
        return number;
    }
    private ProblemRequests.Write draft(String title,String rank,int level) {
        return new ProblemRequests.Write(title,"목록 검증 본문","","","",rank,level,1000,128,"STDIN",List.of(),
            List.of(new ProblemRequests.TestCase("","1")),List.of("PYTHON"),null,null,null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","main.py","print(1)")),null,"PUBLIC");
    }
    private void solved(CustomUserDetails account,int number) {
        long pid=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
        db.update("INSERT INTO user_solved_problems(user_id,problem_id) VALUES(?,?)",account.getUserId(),pid);
    }
    private CustomUserDetails account(Role role) {
        String username="l"+UUID.randomUUID().toString().replace("-","").substring(0,14);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'test-only',?)",username,role.name());
        var user=User.builder().username(username).password("test-only").build();
        ReflectionTestUtils.setField(user,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username));
        ReflectionTestUtils.setField(user,"role",role);
        return new CustomUserDetails(user);
    }
    private Set<Integer> numbers(JsonNode items) {
        var result=new HashSet<Integer>();items.forEach(item->result.add(item.get("problem_number").asInt()));return result;
    }
    private Set<Integer> solvedNumbers(JsonNode items) {
        var result=new HashSet<Integer>();
        items.forEach(item->{assertTrue(item.get("solved").isBoolean());if(item.get("solved").asBoolean())result.add(item.get("problem_number").asInt());});
        return result;
    }
}
