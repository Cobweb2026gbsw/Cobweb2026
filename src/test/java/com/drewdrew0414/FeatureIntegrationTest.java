package com.drewdrew0414;

import com.drewdrew0414.domain.community.*;
import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.submission.*;
import com.drewdrew0414.domain.user.entity.*;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 MySQL 제약·트랜잭션·HTTP 보안으로 제작/그룹/시험/커뮤니티 회귀를 검증합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FeatureIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired SubmissionService submissions;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    @Autowired CommunityService community;
    @Autowired JwtTokenProvider jwt;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;
    CustomUserDetails owner,member,outsider,admin;

    @BeforeEach void setup() {
        owner=user("USER",50); member=user("USER",0); outsider=user("USER",0); admin=user("OPERATOR",0);
    }
    private CustomUserDetails user(String role,int solved) {
        String name="f"+UUID.randomUUID().toString().replace("-","").substring(0,15);
        String password=encoder.encode("Feature-test-only-2026!");
        db.update("INSERT INTO users(username,password,role,solved_count) VALUES(?,?,?,?)",name,password,role,solved);
        var u=User.builder().username(name).password(password).build();
        ReflectionTestUtils.setField(u,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(u,"role",Role.valueOf(role));
        return new CustomUserDetails(u);
    }
    private static String unique() { return UUID.randomUUID().toString().substring(0,12); }
    private long group(CustomUserDetails creator) {
        return groups.create(new GroupRequests.Write("g"+unique(),"그룹 설명"),creator);
    }
    private ProblemRequests.Write draft(String title,Long groupId,String visibility) {
        return new ProblemRequests.Write(title,"문제 본문","입력","출력","제한","ELITE",5,1000,128,"STDIN",
            List.of(new ProblemRequests.Example("1","1","첫 예시"),new ProblemRequests.Example("2","2","둘째 예시")),
            List.of(new ProblemRequests.TestCase("1","1"),new ProblemRequests.TestCase("2","2")),
            List.of("PYTHON"),List.of("구현","테스트"),"직접 제작","https://example.invalid/source",
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","solution.py","print(input())")),groupId,visibility);
    }
    private int publishGroup(long gid) {
        int n=problems.create(draft("그룹문제 "+unique(),gid,"GROUP"),owner);
        problems.requestReview(n,owner);
        problems.review(n,new ProblemRequests.Review("PUBLISHED","확인"),owner);
        return n;
    }
    private long contest(long gid,int number,boolean replay,boolean protection) {
        return contests.create(gid,new ContestRequests.Write("코테 "+unique(),Instant.now().plusSeconds(3600),
            Instant.now().plusSeconds(7200),List.of(number),replay,protection,protection),owner);
    }
    private void activate(long cid) {
        db.update("UPDATE group_contests SET starts_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?",cid);
    }
    private void end(long cid) {
        db.update("UPDATE group_contests SET ends_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",cid);
    }
    private void status(int expected,Runnable action) {
        assertEquals(expected,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());
    }
    private HttpResponse<String> http(String method,String path,Object body,CustomUserDetails user) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path));
        if(user!=null) request.header("Authorization","Bearer "+jwt.generateAccessToken(user.getUserId(),user.getUsername(),user.getUser().getRole().name(),0));
        var publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
        if(body!=null) request.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(request.method(method,publisher).build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void editorRoundTripsAllFieldsAndPrivateReferencesNeverLeak() throws Exception {
        String title="작성 "+unique();
        int n=problems.create(draft(title,null,"PUBLIC"),owner);
        var edit=problems.edit(n,owner);
        assertEquals(2,((List<?>)edit.get("tests")).size());
        assertEquals(2,((List<?>)edit.get("examples")).size());
        assertEquals(List.of("PYTHON"),edit.get("languages"));
        assertEquals(1,((List<?>)edit.get("referenceSolutions")).size());
        assertEquals("PUBLIC",edit.get("visibility")); assertEquals("PRIVATE",edit.get("currentVisibility"));
        status(404,()->problems.edit(n,member));
        problems.setVisibility(n,new ProblemRequests.Visibility("PUBLIC","게시"),admin);
        String body=http("GET","/api/problems/"+n,null,null).body();
        assertFalse(body.contains("referenceSolutions")); assertFalse(body.contains("sourceCode"));
        assertFalse(body.contains("expected_output"));
        assertEquals(403,http("GET","/api/problems/"+n+"/edit",null,member).statusCode());
        problems.setVisibility(n,new ProblemRequests.Visibility("PRIVATE","숨김"),admin);
        assertEquals(404,http("GET","/api/problems/"+n,null,member).statusCode());
        assertEquals(200,http("GET","/api/admin/problems/"+n,null,admin).statusCode());
        assertEquals(403,http("GET","/api/admin/problems",null,member).statusCode());
    }
    @Test void normalizedTitlesAreUniqueWithinScopeButNotAcrossGroups() {
        String title="제목 "+unique();
        problems.create(draft(title,null,"PUBLIC"),owner);
        status(409,()->problems.create(draft("  "+title.replace(" ","   ")+"  ",null,"PRIVATE"),member));
        long a=group(owner),b=group(owner);
        problems.create(draft(title,a,"GROUP"),owner);
        problems.create(draft(title,b,"GROUP"),owner);
        status(409,()->problems.create(draft(title,a,"GROUP"),owner));
    }
    @Test void concurrentDuplicateCreationHasExactlyOneWinner() throws Exception {
        var r=draft("동시제목 "+unique(),null,"PUBLIC");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->{try{problems.create(r,owner);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            var second=executor.submit(()->{try{problems.create(r,member);return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            assertEquals(Set.of(200,409),Set.of(first.get(),second.get()));
        }
    }
    @Test void draftCanBeSavedWithoutJudgeDataButCannotBePublished() {
        var r=new ProblemRequests.Write("빈초안 "+unique(),"본문","","","","INITIATE",5,1000,128,"STDIN",List.of(),List.of());
        int n=problems.create(r,owner);
        status(409,()->problems.requestReview(n,owner));
        status(409,()->problems.setVisibility(n,new ProblemRequests.Visibility("PUBLIC",""),admin));
    }
    @Test void groupRequiresFiftySolvedAndAtMostThreeMemberships() {
        status(403,()->group(member));
        long a=group(owner),b=group(owner),c=group(owner);
        status(409,()->group(owner));
        groups.join(a,member); groups.join(a,member); groups.join(b,member); groups.join(c,member);
        assertEquals(3,groups.mine(member).stream().filter(g->!Boolean.TRUE.equals(g.get("systemGroup"))).count());
        assertEquals(4,groups.mine(member).size());
        long d=group(user("USER",50));
        status(409,()->groups.join(d,member));
        groups.leave(a,member); groups.join(d,member); assertEquals(4,groups.mine(member).size());
        status(409,()->groups.leave(b,owner));
    }
    @Test void eightRolesAndFeatureTogglesAreEnforcedServerSide() {
        long gid=group(owner); groups.join(gid,member);
        assertEquals(5,groups.roles(gid,owner).size());
        long custom=groups.createRole(gid,new GroupRequests.RoleWrite("제작전용",Set.of(GroupPermission.CREATE_PROBLEMS)),owner);
        groups.createRole(gid,new GroupRequests.RoleWrite("읽기",Set.of(GroupPermission.VIEW_PROBLEMS)),owner);
        groups.createRole(gid,new GroupRequests.RoleWrite("없음",Set.of()),owner);
        status(409,()->groups.createRole(gid,new GroupRequests.RoleWrite("아홉",Set.of()),owner));
        groups.changeMemberRole(gid,member.getUserId(),custom,owner);
        assertTrue(groups.can(gid,member,GroupPermission.CREATE_PROBLEMS));
        assertFalse(groups.can(gid,member,GroupPermission.TAKE_CONTESTS));
        int n=problems.create(draft("권한 "+unique(),gid,"GROUP"),member);
        status(403,()->problems.review(n,new ProblemRequests.Review("ARCHIVED",""),member));
        status(403,()->groups.changeMemberRole(gid,owner.getUserId(),custom,member));
        groups.updateRole(gid,custom,new GroupRequests.RoleWrite("제작꺼짐",Set.of()),owner);
        status(403,()->problems.create(draft("차단 "+unique(),gid,"GROUP"),member));
        status(409,()->groups.deleteRole(gid,custom,owner));
    }
    @Test void groupProblemsStayOutOfGlobalSearchAndOutsidersCannotRead() throws Exception {
        long gid=group(owner); int n=publishGroup(gid);
        assertFalse(problems.list(0,"").stream().anyMatch(p->((Number)p.get("problem_number")).intValue()==n));
        assertEquals(404,http("GET","/api/problems/"+n,null,outsider).statusCode());
        groups.join(gid,member);
        assertEquals(200,http("GET","/api/problems/"+n,null,member).statusCode());
        status(403,()->groups.problems(gid,0,"",outsider));
        status(403,()->problems.create(draft("무권한 "+unique(),gid,"GROUP"),member));
    }
    @Test void limitedRoleManagerCannotPromoteItselfOrCreateMorePowerfulRoles() {
        long gid=group(owner); groups.join(gid,member);
        long limited=groups.createRole(gid,new GroupRequests.RoleWrite("제한관리",Set.of(GroupPermission.MANAGE_ROLES)),owner);
        groups.changeMemberRole(gid,member.getUserId(),limited,owner);
        var escalated=new GroupRequests.RoleWrite("승격",EnumSet.allOf(GroupPermission.class));
        status(403,()->groups.updateRole(gid,limited,escalated,member));
        status(403,()->groups.createRole(gid,escalated,member));
        assertFalse(groups.can(gid,member,GroupPermission.VIEW_CONTEST_RESULTS));
        assertEquals(6,groups.roles(gid,owner).size());
    }
    @Test void examUsesServerTimeSeparateStatisticsReplayGateAndFrozenQuestions() throws Exception {
        long gid=group(owner); groups.join(gid,member); groups.join(gid,outsider);
        int n=publishGroup(gid); long cid=contest(gid,n,false,true);
        var staffProblem=groups.problems(gid,0,"",owner).stream()
            .filter(p->((Number)p.get("problemNumber")).intValue()==n).findFirst().orElseThrow();
        assertEquals(true,staffProblem.get("usedInContest"));
        assertTrue(groups.problems(gid,0,"",member).stream()
            .noneMatch(p->((Number)p.get("problemNumber")).intValue()==n));
        status(403,()->submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member));
        assertEquals(404,http("GET","/api/problems/"+n,null,member).statusCode());
        status(409,()->problems.update(n,draft("변경 "+unique(),gid,"GROUP"),owner));
        activate(cid);
        assertEquals(200,http("GET","/api/groups/"+gid+"/contests/"+cid+"/problems/"+n,null,member).statusCode());
        status(404,()->submissions.submit(n,"print(input())",Language.PYTHON,member));
        long sid=submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member);
        String token=UUID.randomUUID().toString();
        db.update("UPDATE submissions SET status='RUNNING',judge_token=? WHERE id=?",token,sid);
        var s=db.queryForMap("SELECT * FROM submissions WHERE id=?",sid);
        long pid=((Number)s.get("problem_id")).longValue();
        var cases=submissions.cases(pid).stream().map(t->new SubmissionService.JudgeResult(((Number)t.get("id")).longValue(),"ACCEPTED",4)).toList();
        submissions.finish(s,cases); submissions.finish(s,cases);
        assertEquals(0,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,member.getUserId()));
        assertEquals(0,db.queryForObject("SELECT submit_count FROM users WHERE id=?",Integer.class,member.getUserId()));
        assertEquals(0,db.queryForObject("SELECT submission_count FROM problems WHERE id=?",Integer.class,pid));
        assertEquals(0,db.queryForObject("SELECT accepted_count FROM problems WHERE id=?",Integer.class,pid));
        assertTrue(submissions.mine(member).isEmpty());
        assertEquals(1,((Number)contests.results(gid,cid,owner).getFirst().get("solvedCount")).intValue());
        status(403,()->contests.results(gid,cid,member));
        assertEquals("print(input())",submissions.detail(sid,member).get("sourceCode"));
        end(cid);
        status(403,()->submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member));
        status(403,()->submissions.detail(sid,member));
        db.update("UPDATE group_contests SET replay_allowed=TRUE WHERE id=?",cid);
        assertEquals(200,http("GET","/api/groups/"+gid+"/contests/"+cid+"/problems/"+n,null,member).statusCode());
        assertEquals(403,http("GET","/api/groups/"+gid+"/contests/"+cid+"/problems/"+n,null,outsider).statusCode());
        assertEquals("print(input())",submissions.detail(sid,member).get("sourceCode"));
    }
    @Test void examEventsAreOptInDeduplicatedAndRateLimited() {
        long gid=group(owner); groups.join(gid,member); int n=publishGroup(gid);
        long cid=contest(gid,n,false,false); activate(cid);
        var event=new ContestRequests.Event(ContestRequests.EventType.WINDOW_BLUR,UUID.randomUUID());
        status(400,()->contests.event(gid,cid,event,member));
        db.update("UPDATE group_contests SET window_switch_protection=TRUE WHERE id=?",cid);
        contests.event(gid,cid,event,member); contests.event(gid,cid,event,member);
        assertEquals(1,contests.events(gid,cid,null,owner).size());
        for(int i=1;i<60;i++) contests.event(gid,cid,new ContestRequests.Event(ContestRequests.EventType.WINDOW_BLUR,UUID.randomUUID()),member);
        status(429,()->contests.event(gid,cid,new ContestRequests.Event(ContestRequests.EventType.WINDOW_BLUR,UUID.randomUUID()),member));
        assertEquals(60,contests.events(gid,cid,member.getUserId(),owner).size());
        status(403,()->contests.events(gid,cid,null,member));
    }
    @Test void simultaneousExamSubmissionAndProtectionEventDoNotDeadlock() throws Exception {
        long gid=group(owner); groups.join(gid,member); int n=publishGroup(gid);
        long cid=contest(gid,n,false,true); activate(cid);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<3;i++) {
                var gate=new java.util.concurrent.CountDownLatch(1);
                var submit=executor.submit(()->{gate.await();return submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member);});
                var event=executor.submit(()->{gate.await();contests.event(gid,cid,new ContestRequests.Event(ContestRequests.EventType.WINDOW_BLUR,UUID.randomUUID()),member);return true;});
                gate.countDown(); assertTrue(submit.get(10,java.util.concurrent.TimeUnit.SECONDS)>0);
                assertTrue(event.get(10,java.util.concurrent.TimeUnit.SECONDS));
            }
        }
        assertEquals(3,contests.events(gid,cid,null,owner).size());
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM submissions WHERE contest_id=?",Integer.class,cid));
    }
    @Test void archivedGroupProblemStillExistsInExamReplay() {
        long gid=group(owner); groups.join(gid,member); int n=publishGroup(gid);
        long cid=contest(gid,n,true,false); activate(cid);
        submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member);
        problems.review(n,new ProblemRequests.Review("ARCHIVED","일반 그룹 목록에서 숨김"),owner);
        assertEquals("ARCHIVED",contests.problem(gid,cid,n,member).get("status"));
        submissions.submitContest(cid,n,"print(input())",Language.PYTHON,member);
        end(cid);
        assertEquals("ARCHIVED",contests.problem(gid,cid,n,member).get("status"));
        status(409,()->problems.update(n,draft("변경 "+unique(),gid,"GROUP"),owner));
    }
    @Test void commentPagingAndQuestionLinkSnapshotsPreserveDataWithoutLeaks() {
        long post=community.createPost(new CommunityRequests.PostWrite("댓글 페이지","내용","GENERAL",null),member);
        for(int i=0;i<23;i++) community.createComment(post,new CommunityRequests.CommentWrite(null,"댓글 "+i),member);
        var detail=community.post(post,false,null);
        assertEquals(20,((List<?>)detail.get("comments")).size());
        assertEquals(23,((Number)detail.get("commentsTotal")).intValue());
        assertEquals(3,((List<?>)community.comments(post,null,1,null).get("items")).size());
        int n=problems.create(draft("공개 질문 "+unique(),null,"PUBLIC"),owner);
        problems.setVisibility(n,new ProblemRequests.Visibility("PUBLIC",""),admin);
        long question=community.createPost(new CommunityRequests.PostWrite("문제 질문","내용","PROBLEM_QNA",n),member);
        problems.setVisibility(n,new ProblemRequests.Visibility("PRIVATE",""),admin);
        assertNull(community.post(question,false,null).get("problemNumber"));
        assertEquals(n,((Number)community.postHistory(question,0,admin).getFirst().get("newProblemNumber")).intValue());
    }
    @Test void communityEditsDeletesAndRestoresPreserveEveryVersion() throws Exception {
        long post=community.createPost(new CommunityRequests.PostWrite("하드웨어 질문","원문","HARDWARE",null),member);
        status(403,()->community.updatePost(post,new CommunityRequests.PostUpdate("수정","침범","HARDWARE",null,1L),outsider));
        community.updatePost(post,new CommunityRequests.PostUpdate("하드웨어 질문 수정","새 내용","HARDWARE",null,1L),member);
        status(409,()->community.updatePost(post,new CommunityRequests.PostUpdate("오래된 수정","내용","HARDWARE",null,1L),member));
        assertEquals(403,http("GET","/api/admin/community/posts/"+post+"/history",null,member).statusCode());
        var history=community.postHistory(post,0,admin);
        assertEquals(2,history.size()); assertEquals("원문",history.getFirst().get("oldBody"));
        community.updatePost(post,new CommunityRequests.PostUpdate("관리자 수정","관리 내용","SOFTWARE",null,2L),admin);
        community.deletePost(post,3,admin);
        assertEquals(404,http("GET","/api/community/posts/"+post,null,null).statusCode());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM community_posts WHERE id=?",Integer.class,post));
        assertEquals("관리 내용",community.adminPost(post,admin).get("body"));
        community.restorePost(post,new CommunityRequests.Restore(4L,"복원"),admin);
        assertEquals(5,community.postHistory(post,0,admin).size());
        assertEquals(200,http("GET","/api/community/posts/"+post,null,null).statusCode());
    }
    @Test void hiddenCommunityProblemLinkSurvivesTextOnlyEditsWithoutDisclosingItsNumber() throws Exception {
        int number=problems.create(draft("연결 보존 "+unique(),null,"PUBLIC"),owner);
        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC",""),admin);
        long post=community.createPost(new CommunityRequests.PostWrite("연결 보존","원문","PROBLEM_QNA",number),member);
        problems.setVisibility(number,new ProblemRequests.Visibility("PRIVATE",""),admin);
        var own=json.readTree(http("GET","/api/community/posts/"+post,null,member).body()).get("data");
        assertTrue(own.get("problemNumber").isNull());
        assertTrue(own.get("problemLinkHidden").asBoolean());
        var anonymous=json.readTree(http("GET","/api/community/posts/"+post,null,null).body()).get("data");
        assertTrue(anonymous.get("problemNumber").isNull());
        assertFalse(anonymous.get("problemLinkHidden").asBoolean());
        assertEquals(403,http("PUT","/api/community/posts/"+post,
            new CommunityRequests.PostUpdate("침범","본문","PROBLEM_QNA",null,1L,true),outsider).statusCode());
        assertEquals(200,http("PUT","/api/community/posts/"+post,
            new CommunityRequests.PostUpdate("제목 수정","본문 수정","PROBLEM_QNA",null,1L,true),member).statusCode());
        assertNotNull(db.queryForObject("SELECT problem_id FROM community_posts WHERE id=?",Long.class,post));
        assertEquals(number,((Number)community.postHistory(post,0,admin).getFirst().get("newProblemNumber")).intValue());
        assertEquals("본문 수정",community.post(post,false,member).get("body"));
        assertEquals("PROBLEM_QNA",community.post(post,false,member).get("category"));
        assertEquals(400,http("PUT","/api/community/posts/"+post,
            new CommunityRequests.PostUpdate("새 연결","본문","PROBLEM_QNA",number,2L,true),member).statusCode());
        assertEquals(200,http("PUT","/api/community/posts/"+post,
            new CommunityRequests.PostUpdate("관리자 수정","본문","PROBLEM_QNA",null,2L,true),admin).statusCode());
        problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC",""),admin);
        assertEquals(number,((Number)community.post(post,false,null).get("problemNumber")).intValue());
        // 기존 API의 명시적인 null 전체 교체는 여전히 공개 연결 제거로 동작합니다.
        assertEquals(200,http("PUT","/api/community/posts/"+post,
            Map.of("title","연결 제거","body","본문","category","GENERAL","version",3L),member).statusCode());
        assertNull(community.post(post,false,null).get("problemNumber"));
        assertFalse((Boolean)community.post(post,false,member).get("problemLinkHidden"));
    }
    @Test void commentDepthTwoDeletedParentAndModeratorHistoryWork() {
        long post=community.createPost(new CommunityRequests.PostWrite("전공 질문","본문","MAJOR_QNA",null),member);
        long a=community.createComment(post,new CommunityRequests.CommentWrite(null,"댓글 원문"),member);
        long b=community.createComment(post,new CommunityRequests.CommentWrite(a,"대댓글"),outsider);
        long c=community.createComment(post,new CommunityRequests.CommentWrite(b,"대대댓글"),owner);
        status(400,()->community.createComment(post,new CommunityRequests.CommentWrite(c,"3단계"),member));
        community.updateComment(a,new CommunityRequests.CommentUpdate("댓글 수정",1L),member);
        community.updateComment(a,new CommunityRequests.CommentUpdate("관리자 수정",2L),admin);
        status(403,()->community.commentHistory(a,0,member));
        community.deleteComment(a,3,admin);
        var comments=(List<Map<String,Object>>)community.post(post,false,null).get("comments");
        assertEquals(1,comments.size()); assertNull(comments.getFirst().get("body"));
        var replies=(List<Map<String,Object>>)community.comments(post,a,0,null).get("items");
        assertEquals("대댓글",replies.getFirst().get("body"));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM community_comments WHERE post_id=?",Integer.class,post));
        community.restoreComment(a,new CommunityRequests.Restore(4L,"복원"),admin);
        assertEquals(5,community.commentHistory(a,0,admin).size());
    }
    @Test void noticesAreAdminOnlyAndPrivateProblemReferencesAreRejected() {
        status(403,()->community.createPost(new CommunityRequests.PostWrite("위조 공지","내용","NOTICE",null),member));
        long notice=community.createPost(new CommunityRequests.PostWrite("공지","내용","NOTICE",null),admin);
        assertEquals("NOTICE",community.post(notice,false,null).get("category"));
        int n=problems.create(draft("비공개 "+unique(),null,"PRIVATE"),owner);
        status(400,()->community.createPost(new CommunityRequests.PostWrite("질문","내용","PROBLEM_QNA",n),member));
        long p=community.createPost(new CommunityRequests.PostWrite("일반글","내용","GENERAL",null),member);
        status(403,()->community.updatePost(p,new CommunityRequests.PostUpdate("공지 전환","내용","NOTICE",null,1L),member));
    }
}
