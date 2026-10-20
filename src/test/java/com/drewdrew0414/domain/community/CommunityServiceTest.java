package com.drewdrew0414.domain.community;

import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 공개 댓글 페이지와 비공개 전환된 문제 연결의 노출 경계를 검증합니다. */
class CommunityServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final CommunityService service=new CommunityService(db);

    @Test void hiddenLinkedProblemNumberIsNotReturnedByPublicDetail() {
        var post=new HashMap<String,Object>();
        post.put("id",12L); post.put("title","질문"); post.put("body","본문");
        post.put("category","PROBLEM_QNA"); post.put("author_username","writer");
        post.put("version",1L); post.put("is_deleted",false);
        post.put("created_at",Timestamp.valueOf("2026-10-12 13:35:00"));
        post.put("updated_at",Timestamp.valueOf("2026-10-12 13:36:00"));
        post.put("problem_number",1001); post.put("problem_status","PUBLISHED");
        post.put("problem_visibility","PRIVATE"); post.put("problem_group_id",null);
        when(db.queryForList(contains("SELECT cp.*"),eq(12L))).thenReturn(List.of(post));
        emptyRootComments(12L);

        var result=service.post(12L,false,null);

        assertNull(result.get("problemNumber"));
        assertEquals(0L,result.get("commentsTotal"));
        assertEquals("2026-10-12T13:35:00Z",result.get("createdAt"));
        assertEquals("2026-10-12T13:36:00Z",result.get("updatedAt"));
    }

    @Test void deletedParentKeepsReplyCountButHidesItsBody() {
        var comment=new HashMap<String,Object>();
        comment.put("id",32L); comment.put("parent_id",null); comment.put("depth",0);
        comment.put("body","삭제 전 본문"); comment.put("is_deleted",true);
        comment.put("author_username","writer"); comment.put("version",2L);
        comment.put("reply_count",2L);
        comment.put("created_at",Timestamp.valueOf("2026-10-12 13:35:00"));
        when(db.queryForList("SELECT is_deleted FROM community_posts WHERE id=?",12L))
            .thenReturn(List.of(Map.of("is_deleted",false)));
        when(db.queryForList(contains("SELECT cc.*"),eq(12L),isNull(),isNull(),eq(0)))
            .thenReturn(List.of(comment));
        when(db.queryForObject(contains("SELECT COUNT(*) FROM community_comments cc"),eq(Long.class),
            eq(12L),isNull(),isNull())).thenReturn(1L);

        var page=service.comments(12L,null,0,null);
        var item=((List<Map<String,Object>>)page.get("items")).getFirst();

        assertNull(item.get("body"));
        assertEquals("삭제된 댓글입니다.",item.get("deletedMessage"));
        assertEquals(2L,item.get("replyCount"));
        assertEquals(1L,page.get("total"));
        assertEquals("2026-10-12T13:35:00Z",item.get("createdAt"));
    }

    @Test void revisionTimeIsSerializedAsUtc() {
        var admin=mock(CustomUserDetails.class);
        when(admin.isEnabled()).thenReturn(true);
        when(admin.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        when(db.queryForList(contains("FROM community_post_revisions"),eq(12L),eq(0)))
            .thenReturn(List.of(Map.of("editedAt",Timestamp.valueOf(LocalDateTime.of(2026,10,12,13,35)))));

        var history=service.postHistory(12L,0,admin);

        assertEquals("2026-10-12T13:35:00Z",history.getFirst().get("editedAt"));
    }

    @Test void listingTimeIsSerializedAsUtc() {
        when(db.queryForList(contains("FROM community_posts cp LEFT JOIN"),eq(false),eq(""),eq(""),
            eq("%%"),eq("%%"),eq(0))).thenReturn(List.of(Map.of(
                "id",12L,"createdAt",Timestamp.valueOf("2026-10-12 13:35:00"),
                "updatedAt",Timestamp.valueOf("2026-10-12 13:36:00"))));
        when(db.queryForObject(contains("SELECT COUNT(*) FROM community_posts cp"),eq(Long.class),
            eq(false),eq(""),eq(""),eq("%%"),eq("%%"))).thenReturn(1L);

        var listing=service.posts(0,"","",false,null);
        var item=((List<Map<String,Object>>)listing.get("items")).getFirst();

        assertEquals("2026-10-12T13:35:00Z",item.get("createdAt"));
        assertEquals("2026-10-12T13:36:00Z",item.get("updatedAt"));
    }

    @Test void everyPostMutationLocksEditorBeforePostOrLinkedProblem() {
        var editor=operator(42L);
        var write=new CommunityRequests.PostWrite("질문","본문","PROBLEM_QNA",1001);
        var update=new CommunityRequests.PostUpdate("질문","본문","PROBLEM_QNA",null,1L);
        var restore=new CommunityRequests.Restore(1L,"복원");
        List<Runnable> mutations=List.of(
            () -> service.createPost(write,editor),
            () -> service.updatePost(12L,update,editor),
            () -> service.deletePost(12L,1L,editor),
            () -> service.restorePost(12L,restore,editor));

        for(int i=0;i<mutations.size();i++) {
            reset(db);
            allowUserLock(42L);
            assertThrows(ResponseStatusException.class,mutations.get(i)::run);
            var order=inOrder(db);
            order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
            if(i==0) order.verify(db).queryForList(
                contains("SELECT id FROM problems WHERE problem_number=?"),eq(1001));
            else order.verify(db).queryForList("SELECT * FROM community_posts WHERE id=? FOR UPDATE",12L);
        }
    }

    @Test void postUpdateLocksEditorBeforeSwitchingToAnotherLinkedProblem() {
        var editor=operator(42L);
        allowUserLock(42L);
        var old=new HashMap<String,Object>();
        old.put("id",12L); old.put("author_id",42L); old.put("category","PROBLEM_QNA");
        old.put("title","기존"); old.put("body","기존 본문"); old.put("version",1L);
        old.put("is_deleted",false);
        when(db.queryForList("SELECT * FROM community_posts WHERE id=? FOR UPDATE",12L))
            .thenReturn(List.of(old));
        when(db.queryForList(contains("SELECT id FROM problems WHERE problem_number=?"),eq(1001)))
            .thenReturn(List.of(Map.of("id",7L)));
        when(db.update(contains("UPDATE community_posts SET title"),
            eq("새 질문"),eq("새 본문"),eq("PROBLEM_QNA"),eq(7L),eq(2L),eq(12L),eq(1L)))
            .thenReturn(1);

        service.updatePost(12L,
            new CommunityRequests.PostUpdate("새 질문","새 본문","PROBLEM_QNA",1001,1L),editor);

        var order=inOrder(db);
        order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
        order.verify(db).queryForList("SELECT * FROM community_posts WHERE id=? FOR UPDATE",12L);
        order.verify(db).queryForList(contains("SELECT id FROM problems WHERE problem_number=?"),eq(1001));
        order.verify(db).update(contains("UPDATE community_posts SET title"),
            eq("새 질문"),eq("새 본문"),eq("PROBLEM_QNA"),eq(7L),eq(2L),eq(12L),eq(1L));
    }

    @Test void everyCommentMutationLocksEditorBeforePostOrComment() {
        var editor=operator(42L);
        var write=new CommunityRequests.CommentWrite(null,"댓글");
        var update=new CommunityRequests.CommentUpdate("수정",1L);
        var restore=new CommunityRequests.Restore(1L,"복원");
        List<Runnable> mutations=List.of(
            () -> service.createComment(12L,write,editor),
            () -> service.updateComment(31L,update,editor),
            () -> service.deleteComment(31L,1L,editor),
            () -> service.restoreComment(31L,restore,editor));

        for(int i=0;i<mutations.size();i++) {
            reset(db);
            allowUserLock(42L);
            if(i==1 || i==2)
                when(db.queryForList("SELECT post_id FROM community_comments WHERE id=?",31L))
                    .thenReturn(List.of(Map.of("post_id",12L)));
            assertThrows(ResponseStatusException.class,mutations.get(i)::run);
            var order=inOrder(db);
            order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
            if(i==1 || i==2)
                order.verify(db).queryForList("SELECT post_id FROM community_comments WHERE id=?",31L);
            if(i==3) order.verify(db).queryForList("SELECT * FROM community_comments WHERE id=? FOR UPDATE",31L);
            else order.verify(db).queryForList("SELECT * FROM community_posts WHERE id=? FOR UPDATE",12L);
        }
    }

    @Test void missingEditorRowReturnsUnauthorizedBeforeAnyOtherCommunityRead() {
        var editor=operator(42L);
        var write=new CommunityRequests.PostWrite("질문","본문","PROBLEM_QNA",1001);
        var update=new CommunityRequests.PostUpdate("질문","본문","PROBLEM_QNA",null,1L);
        var comment=new CommunityRequests.CommentWrite(null,"댓글");
        var commentUpdate=new CommunityRequests.CommentUpdate("수정",1L);
        var restore=new CommunityRequests.Restore(1L,"복원");
        List<Runnable> mutations=List.of(
            () -> service.createPost(write,editor),
            () -> service.updatePost(12L,update,editor),
            () -> service.deletePost(12L,1L,editor),
            () -> service.restorePost(12L,restore,editor),
            () -> service.createComment(12L,comment,editor),
            () -> service.updateComment(31L,commentUpdate,editor),
            () -> service.deleteComment(31L,1L,editor),
            () -> service.restoreComment(31L,restore,editor));

        for(var mutation:mutations) {
            reset(db);
            var error=assertThrows(ResponseStatusException.class,mutation::run);
            assertEquals(401,error.getStatusCode().value());
            verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
            verifyNoMoreInteractions(db);
        }
    }

    @Test void authenticationAndRequestValidationStillPrecedeDatabaseLocks() {
        var authenticated=operator(42L);
        var anonymous=operator(42L);
        var member=mock(CustomUserDetails.class);
        when(anonymous.isEnabled()).thenReturn(false);
        when(member.isEnabled()).thenReturn(true);
        when(member.getAuthorities()).thenAnswer(invocation -> List.<SimpleGrantedAuthority>of());
        assertEquals(401,assertThrows(ResponseStatusException.class,
            () -> service.createPost(new CommunityRequests.PostWrite("제목","본문","GENERAL",null),anonymous))
            .getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,
            () -> service.restorePost(12L,new CommunityRequests.Restore(1L,null),member))
            .getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,
            () -> service.createPost(null,authenticated)).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,
            () -> service.restoreComment(31L,null,authenticated)).getStatusCode().value());
        verifyNoInteractions(db);
    }

    private void allowUserLock(long uid) {
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid))
            .thenReturn(List.of(Map.of("id",uid)));
    }

    private static CustomUserDetails operator(long uid) {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(uid);
        when(user.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        return user;
    }

    private void emptyRootComments(long id) {
        when(db.queryForList("SELECT is_deleted FROM community_posts WHERE id=?",id))
            .thenReturn(List.of(Map.of("is_deleted",false)));
        when(db.queryForList(contains("SELECT cc.*"),eq(id),isNull(),isNull(),eq(0)))
            .thenReturn(List.of());
        when(db.queryForObject(contains("SELECT COUNT(*) FROM community_comments cc"),eq(Long.class),
            eq(id),isNull(),isNull())).thenReturn(0L);
    }
}
