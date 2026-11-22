package com.drewdrew0414;

import com.drewdrew0414.domain.community.*;
import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.moderation.*;
import com.drewdrew0414.domain.problem.*;
import com.drewdrew0414.domain.user.entity.*;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ModerationOperationsIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    @Autowired ProblemReleaseService releases;
    @Autowired RestrictionService restrictions;
    @Autowired CommunityService community;
    @Autowired ProblemIssueService issues;
    @Autowired GroupService groups;
    @Autowired ContestService contests;
    CustomUserDetails admin,writer,other;
    @BeforeEach void accounts() {admin=user("OPERATOR");writer=user("USER");other=user("USER");}
    static String key(){return UUID.randomUUID().toString().replace("-","").substring(0,14);}
    CustomUserDetails user(String role){
        String name=key();
        long id=db.queryForObject("INSERT INTO users(username,password,role,solved_count) VALUES(?,'unused',?,50) RETURNING id",Long.class,name,role);
        var u=User.builder().username(name).password("unused").build();
        ReflectionTestUtils.setField(u,"id",id);ReflectionTestUtils.setField(u,"role",Role.valueOf(role));
        return new CustomUserDetails(u);
    }
    int draft(Long gid){
        var p=new ProblemRequests.Write(key(),"본문","입력","출력","제약","ELITE",5,1000,128,"STDIN",
            List.of(new ProblemRequests.Example("1","1","예제")),List.of(new ProblemRequests.TestCase("1","1")),
            List.of("PYTHON"),List.of("구현"),"직접 제작",null,
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","solution.py","print(input())")),gid,gid==null?"PUBLIC":"GROUP");
        return problems.create(p,writer);
    }
    int pending(Long gid){int n=draft(gid);problems.requestReview(n,writer);return n;}
    int published(Long gid){int n=pending(gid);problems.review(n,new ProblemRequests.Review("PUBLISHED","검수"),admin);return n;}
    long submit(int number,Long cid,CustomUserDetails member){
        return db.queryForObject("""
          INSERT INTO submissions(user_id,problem_id,language,source_code,contest_id,status)
          SELECT ?,id,'PYTHON','print(1)',?,'WRONG_ANSWER' FROM problems WHERE problem_number=? RETURNING id
          """,Long.class,member.getUserId(),cid,number);
    }
    void denied(int status,Runnable call){assertEquals(status,assertThrows(ApiException.class,call::run).getStatusCode().value());}
    ProblemIssueService.NewIssue appeal(int n,long sid){return new ProblemIssueService.NewIssue(n,sid,ProblemIssueService.Category.JUDGING_APPEAL,"출력 확인 요청");}

    @Test void issuesValidateOwnershipAndAllowPrivilegedAssignmentAndResponse(){
        int number=published(null);long sub=submit(number,null,writer);
        denied(404,()->issues.create(null,null,appeal(number,sub),other));
        long id=issues.create(null,null,appeal(number,sub),writer);
        assertTrue(issues.list(null,null,false,null,0,other).isEmpty());
        denied(403,()->issues.list(null,null,true,null,0,writer));
        denied(400,()->issues.review(id,null,null,new ProblemIssueService.Review(1,ProblemIssueService.Status.RESOLVED,"확인",other.getUsername()),admin));
        issues.review(id,null,null,new ProblemIssueService.Review(1,ProblemIssueService.Status.RESOLVED,"정답을 재확인했습니다.",admin.getUsername()),admin);
        var row=issues.list(null,null,false,null,0,writer).getFirst();
        assertEquals(admin.getUsername(),row.get("assignee"));
        assertEquals("정답을 재확인했습니다.",row.get("response"));
        denied(409,()->issues.review(id,null,null,new ProblemIssueService.Review(1,ProblemIssueService.Status.OPEN,"",null),admin));
    }

    @Test void contestAppealsNeverAppearInGeneralInboxAndReplayChangesImmediatelyRevokeAccess(){
        long gid=groups.create(new GroupRequests.Write("g"+key(),"그룹"),writer);
        int n=published(gid);
        long cid=contests.create(gid,new ContestRequests.Write(key(),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),List.of(n),false,false,false,true),writer);
        groups.join(gid,other);
        db.update("UPDATE group_contests SET starts_at=(clock_timestamp() AT TIME ZONE 'UTC')-INTERVAL '1 minute' WHERE id=?",cid);
        long sub=submit(n,cid,other);
        denied(404,()->issues.create(null,null,appeal(n,sub),writer));
        long id=issues.create(gid,cid,appeal(n,sub),other);
        assertTrue(issues.list(null,null,true,null,0,admin).stream().noneMatch(i->i.get("id").equals(id)));
        denied(404,()->issues.review(id,null,null,new ProblemIssueService.Review(1,ProblemIssueService.Status.OPEN,"",null),admin));
        assertEquals(1,issues.list(gid,cid,true,null,0,writer).size());
        denied(403,()->issues.list(gid,cid,true,null,0,other));
        db.update("UPDATE group_contests SET ends_at=(clock_timestamp() AT TIME ZONE 'UTC')-INTERVAL '1 second' WHERE id=?",cid);
        denied(403,()->issues.list(gid,cid,false,null,0,other));
    }

    @Test void restrictionsCoverEditsAndRepliesExpireOrCanBeRevokedSeparately(){
        var post=new CommunityRequests.PostWrite("질문","내용","GENERAL",null);
        long postId=community.createPost(post,writer);
        long commentId=community.createComment(postId,new CommunityRequests.CommentWrite(null,"기존 댓글"),writer);
        var req=new RestrictionService.Request(writer.getUsername(),RestrictionService.Feature.COMMENT,"도배",OffsetDateTime.now().plusDays(1));
        denied(403,()->restrictions.restrict(req,writer));
        long restriction=restrictions.restrict(req,admin);
        denied(409,()->restrictions.restrict(req,admin));
        denied(403,()->community.createComment(postId,new CommunityRequests.CommentWrite(commentId,"대댓글"),writer));
        denied(403,()->community.updateComment(commentId,new CommunityRequests.CommentUpdate("바꿈",1L),writer));
        assertTrue(community.createPost(post,writer)>0);
        restrictions.revoke(restriction,admin);
        assertTrue(community.createComment(postId,new CommunityRequests.CommentWrite(null,"허용"),writer)>0);
        long expired=restrictions.restrict(new RestrictionService.Request(writer.getUsername(),RestrictionService.Feature.POST,"도배",OffsetDateTime.now().plusHours(1)),admin);
        denied(403,()->community.createPost(post,writer));
        denied(403,()->community.updatePost(postId,new CommunityRequests.PostUpdate("수정","제한","GENERAL",null,1L),writer));
        db.update("UPDATE community_restrictions SET created_at=now()-INTERVAL '2 hours',expires_at=now()-INTERVAL '1 hour' WHERE id=?",expired);
        assertTrue(community.createPost(post,writer)>0);
        assertEquals("EXPIRED",restrictions.list(writer.getUsername(),0,admin).getFirst().get("status"));
    }

    long schedule(int... ns){return releases.schedule(new ProblemReleaseService.Request(Arrays.stream(ns).boxed().toList(),OffsetDateTime.now().plusDays(1)),admin);}
    String state(int n){return db.queryForObject("SELECT visibility FROM problems WHERE problem_number=?",String.class,n);}
    String batchStatus(long id){return db.queryForObject("SELECT status FROM problem_release_batches WHERE id=?",String.class,id);}
    void due(long id){db.update("UPDATE problem_release_batches SET scheduled_for=clock_timestamp()-INTERVAL '1 second' WHERE id=?",id);}

    @Test @Transactional void allReviewedProblemsBecomePublicTogetherAndOnlyOnce(){
        int a=pending(null),b=pending(null);long id=schedule(a,b);
        assertEquals("PRIVATE",state(a));assertEquals("PENDING",batchStatus(id));
        due(id);assertTrue(releases.runNextDue());
        assertEquals("PUBLIC",state(a));assertEquals("PUBLIC",state(b));assertEquals("PUBLISHED",batchStatus(id));
        assertFalse(releases.runNextDue());
    }
    @Test @Transactional void revisedProblemStopsEntireBatchAndCancellationKeepsProblemsPrivate(){
        int a=pending(null),b=pending(null);long id=schedule(a,b);
        problems.review(b,new ProblemRequests.Review("ARCHIVED","다시 확인"),admin);
        due(id);assertTrue(releases.runNextDue());assertEquals("FAILED",batchStatus(id));
        assertEquals("PRIVATE",state(a));assertEquals("PRIVATE",state(b));
        long cancelled=schedule(a);releases.cancel(cancelled,admin);due(cancelled);
        assertFalse(releases.runNextDue());assertEquals("CANCELLED",batchStatus(cancelled));assertEquals("PRIVATE",state(a));
    }
    @Test void invalidSelectionRollsBackApprovalsAndDuplicateReservationIsRejected(){
        int a=pending(null),draft=draft(null);
        denied(409,()->schedule(a,draft));
        assertEquals("PENDING_REVIEW",db.queryForObject("SELECT status FROM problems WHERE problem_number=?",String.class,a));
        long batch=schedule(a);denied(409,()->schedule(a));releases.cancel(batch,admin);
        long gid=groups.create(new GroupRequests.Write("g"+key(),"그룹"),writer);
        int group=pending(gid);denied(409,()->schedule(group));
    }
}
