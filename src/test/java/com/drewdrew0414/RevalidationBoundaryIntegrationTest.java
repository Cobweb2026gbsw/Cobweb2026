package com.drewdrew0414;

import com.drewdrew0414.domain.community.*;
import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.submission.*;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-10-13 재검증: 상태 이력 FK 잠금과 시험 시각의 실제 DB 경계를 확인합니다. */
@SpringBootTest
@ActiveProfiles("test")
class RevalidationBoundaryIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    @Autowired SubmissionService submissions;
    @Autowired CommunityService community;

    private record Fixture(CustomUserDetails owner,long gid,long cid,int number) {}

    private Fixture fixture() {
        String name="r"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,solved_count) VALUES(?,'unused',50)",name);
        long uid=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name);
        var entity=User.builder().username(name).password("unused").build();
        ReflectionTestUtils.setField(entity,"id",uid);
        var owner=new CustomUserDetails(entity);
        long gid=groups.create(new GroupRequests.Write(name,"재검증 전용"),owner);
        int number=problems.create(new ProblemRequests.Write("경계 "+UUID.randomUUID(),"본문","","","",
            "INITIATE",5,1000,128,"STDIN",List.of(),List.of(new ProblemRequests.TestCase("","1")),
            List.of("PYTHON"),List.of(),null,null,List.of(new ProblemRequests.ReferenceSolution(
                "PYTHON","main.py","print(1)")),gid,"GROUP"),owner);
        problems.requestReview(number,owner);
        problems.review(number,new ProblemRequests.Review("PUBLISHED","준비"),owner);
        long cid=contests.create(gid,new ContestRequests.Write("경계 시험",Instant.now().plusSeconds(60),
            Instant.now().plusSeconds(3600),List.of(number),true,false,false),owner);
        db.update("UPDATE group_contests SET starts_at=UTC_TIMESTAMP(6),ends_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR) WHERE id=?",cid);
        return new Fixture(owner,gid,cid,number);
    }

    @Test void statusHistoryAndSubmissionCanRunConcurrentlyForTheSameOwner() throws Exception {
        var f=fixture();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<3;i++) {
                var start=new CountDownLatch(1);
                var review=executor.submit(()->{start.await();problems.review(f.number(),
                    new ProblemRequests.Review("ARCHIVED","병렬 보관"),f.owner());return true;});
                var submit=executor.submit(()->{start.await();return submissions.submitContest(
                    f.cid(),f.number(),"print(1)",Language.PYTHON,f.owner());});
                start.countDown();
                assertTrue(review.get(15,TimeUnit.SECONDS));
                long sid=submit.get(15,TimeUnit.SECONDS);
                assertTrue(sid>0);
                // 이 검사는 큐 잠금만 검증하며 실제 채점은 별도 Docker 전체 검사에서 수행합니다.
                db.update("UPDATE submissions SET status='SYSTEM_ERROR' WHERE id=?",sid);
            }
        }
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM submissions WHERE contest_id=?",
            Integer.class,f.cid()));
        assertEquals(50,db.queryForObject("SELECT solved_count FROM users WHERE id=?",Integer.class,f.owner().getUserId()));
    }

    @Test void submittedAndJudgedTimesKeepPrecisionAndUseUtcDtos() {
        var f=fixture();
        long sid=submissions.submitContest(f.cid(),f.number(),"print(1)",Language.PYTHON,f.owner());
        var c=db.queryForMap("SELECT starts_at FROM group_contests WHERE id=?",f.cid());
        var s=db.queryForMap("SELECT * FROM submissions WHERE id=?",sid);
        assertFalse(local(s.get("submitted_at")).isBefore(local(c.get("starts_at"))),
            "유효한 시험 접수 시각을 초 단위로 잘라 시작 전으로 저장하면 안 됩니다.");
        String token=UUID.randomUUID().toString();
        db.update("UPDATE submissions SET status='RUNNING',judge_token=? WHERE id=?",token,sid);
        s=db.queryForMap("SELECT * FROM submissions WHERE id=?",sid);
        var result=submissions.cases(s.get("problem_id")).stream().map(t->new SubmissionService.JudgeResult(
            ((Number)t.get("id")).longValue(),"ACCEPTED",1)).toList();
        submissions.finish(s,result);
        var data=(List<Map<String,Object>>)contests.mine(f.gid(),f.cid(),f.owner()).get("submissions");
        var own=data.getFirst();
        assertTrue(own.get("submittedAt") instanceof String);
        assertTrue(own.get("judgedAt") instanceof String);
        assertFalse(Instant.parse((String)own.get("judgedAt")).isBefore(Instant.parse((String)own.get("submittedAt"))));
        assertEquals("ACCEPTED",own.get("status"));
    }

    @Test void communityProblemRelinkingAndVisibilityChangesDoNotInvertUserLocks() throws Exception {
        assertCommunityRelinkAndVisibility(false);
        assertCommunityRelinkAndVisibility(true);
    }

    private void assertCommunityRelinkAndVisibility(boolean differentActors) throws Exception {
        String name="r"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password,role) VALUES(?,'unused','OPERATOR')",name);
        var entity=User.builder().username(name).password("unused").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",Role.OPERATOR);
        var operator=new CustomUserDetails(entity);
        var author=operator;
        if(differentActors) {
            String authorName="r"+UUID.randomUUID().toString().replace("-","").substring(0,12);
            db.update("INSERT INTO users(username,password) VALUES(?,'unused')",authorName);
            var authorEntity=User.builder().username(authorName).password("unused").build();
            ReflectionTestUtils.setField(authorEntity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,authorName));
            author=new CustomUserDetails(authorEntity);
        }
        final var writer=author;
        int number=problems.create(new ProblemRequests.Write("연결 "+UUID.randomUUID(),"본문","","","",
            "INITIATE",5,1000,128,"STDIN",List.of(),List.of(new ProblemRequests.TestCase("","1")),
            List.of("PYTHON"),List.of(),null,null,List.of(new ProblemRequests.ReferenceSolution(
                "PYTHON","main.py","print(1)")),null,"PUBLIC"),operator);
        long post=community.createPost(new CommunityRequests.PostWrite("연결 검증","본문","GENERAL",null),writer);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            for(int i=0;i<3;i++) {
                long previous=db.queryForObject("SELECT version FROM community_posts WHERE id=?",Long.class,post);
                community.updatePost(post,new CommunityRequests.PostUpdate("연결 검증","본문","GENERAL",null,previous),writer);
                problems.setVisibility(number,new ProblemRequests.Visibility("PUBLIC","병렬 준비"),operator);
                long version=db.queryForObject("SELECT version FROM community_posts WHERE id=?",Long.class,post);
                var start=new CountDownLatch(1);
                var relink=executor.submit(()->{
                    start.await();
                    try {
                        community.updatePost(post,new CommunityRequests.PostUpdate("연결 검증","본문","PROBLEM_QNA",number,version),writer);
                        return 200;
                    } catch(ResponseStatusException e) { return e.getStatusCode().value(); }
                });
                var hide=executor.submit(()->{start.await();problems.setVisibility(number,
                    new ProblemRequests.Visibility("PRIVATE","병렬 비공개"),operator);return true;});
                start.countDown();
                int status=relink.get(15,TimeUnit.SECONDS);
                // 비공개 전환이 먼저 끝났다면 문제 연결 검증의 정상적인 400 응답을 허용합니다.
                assertTrue(status==200 || status==400,"잠금 충돌이나 서버 오류가 발생하면 안 됩니다.");
                assertTrue(hide.get(15,TimeUnit.SECONDS));
                long current=db.queryForObject("SELECT version FROM community_posts WHERE id=?",Long.class,post);
                assertEquals(version+(status==200?1:0),current);
                assertEquals(current,db.queryForObject("SELECT COUNT(*) FROM community_post_revisions WHERE post_id=?",Long.class,post));
            }
        }
    }

    private LocalDateTime local(Object value) {
        return value instanceof Timestamp t?t.toLocalDateTime():(LocalDateTime)value;
    }
}
