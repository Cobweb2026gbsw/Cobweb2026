package com.drewdrew0414.domain.community;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drewdrew0414.global.exception.ApiException;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 게시글·댓글을 물리 삭제하지 않고 현재 상태와 이전/이후 스냅샷을 한 트랜잭션에 기록합니다. */
@Service @RequiredArgsConstructor
public class CommunityService {
    private static final Set<String> CATEGORIES=Set.of(
        "HARDWARE","SOFTWARE","NEWS","PROBLEM_QNA","MAJOR_QNA","GENERAL","NOTICE");
    private final JdbcTemplate db;

    /** 편집 이력의 editor_id FK가 나중에 사용자행을 잠그므로 모든 쓰기에서 먼저 선점합니다. */
    private long lockUser(CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        if(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid).isEmpty())
            throw new ApiException(HttpStatus.UNAUTHORIZED);
        return uid;
    }

    public Map<String,Object> posts(int page,String search,String category,boolean includeDeleted,
                                    CustomUserDetails user) {
        if(includeDeleted) ProblemService.requireOperator(user);
        String selected=category==null?"":category;
        if(!selected.isEmpty() && !CATEGORIES.contains(selected))
            throw new ApiException(HttpStatus.BAD_REQUEST,"게시판 분류를 확인해주세요.");
        String query=Objects.toString(search,"");
        if(query.length()>200) throw new ApiException(HttpStatus.BAD_REQUEST,"검색어가 너무 깁니다.");
        String term="%"+query+"%";
        int offset=offset(page);
        var rows=db.queryForList("""
            SELECT cp.id,cp.title,cp.category,cp.author_username AS authorUsername,
                   cp.version,cp.is_deleted AS deleted,cp.created_at AS createdAt,
                   cp.updated_at AS updatedAt,
                   CASE WHEN p.status='PUBLISHED' AND p.visibility='PUBLIC' AND p.group_id IS NULL
                        THEN p.problem_number ELSE NULL END AS problemNumber,
                   (SELECT COUNT(*) FROM community_comments cc WHERE cc.post_id=cp.id) AS commentCount
            FROM community_posts cp LEFT JOIN problems p ON p.id=cp.problem_id
            WHERE (?=TRUE OR cp.is_deleted=FALSE) AND (?='' OR cp.category=?)
              AND (cp.title LIKE ? OR cp.body LIKE ?)
            ORDER BY (cp.category='NOTICE') DESC,cp.created_at DESC,cp.id DESC
            LIMIT 20 OFFSET ?
            """,includeDeleted,selected,selected,term,term,offset);
        var items=rows.stream().map(CommunityService::withUtcListTimes).toList();
        Long total=db.queryForObject("""
            SELECT COUNT(*) FROM community_posts cp
            WHERE (?=TRUE OR cp.is_deleted=FALSE) AND (?='' OR cp.category=?)
              AND (cp.title LIKE ? OR cp.body LIKE ?)
            """,Long.class,includeDeleted,selected,selected,term,term);
        return Map.of("items",items,"total",total,"page",Math.max(page,0));
    }
    public Map<String,Object> adminPosts(int page,String search,String category,boolean includeDeleted,
                                         CustomUserDetails user) {
        ProblemService.requireOperator(user);
        return posts(page,search,category,includeDeleted,user);
    }
    public Map<String,Object> post(long id,boolean includeDeleted,CustomUserDetails user) {
        if(includeDeleted) ProblemService.requireOperator(user);
        var rows=db.queryForList("""
            SELECT cp.*,p.problem_number,p.status AS problem_status,
                   p.visibility AS problem_visibility,p.group_id AS problem_group_id
            FROM community_posts cp
            LEFT JOIN problems p ON p.id=cp.problem_id WHERE cp.id=?
            """,id);
        if(rows.isEmpty() || (!includeDeleted && deleted(rows.getFirst().get("is_deleted"))))
            throw new ApiException(HttpStatus.NOT_FOUND);
        var p=rows.getFirst();
        boolean gone=deleted(p.get("is_deleted"));
        boolean manage=mayManage(p,user);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",p.get("id"));
        result.put("title",p.get("title"));
        result.put("body",p.get("body"));
        result.put("category",p.get("category"));
        result.put("authorUsername",p.get("author_username"));
        result.put("version",p.get("version"));
        result.put("createdAt",isoUtc(p.get("created_at")));
        result.put("updatedAt",isoUtc(p.get("updated_at")));
        result.put("edited",((Number)p.get("version")).longValue()>1);
        result.put("deleted",gone);
        result.put("canEdit",manage && !gone && (!"NOTICE".equals(p.get("category")) || ProblemService.operator(user)));
        result.put("canDelete",manage && !gone && (!"NOTICE".equals(p.get("category")) || ProblemService.operator(user)));
        result.put("problemNumber",publicProblemNumber(p,includeDeleted));
        // 편집 가능한 작성자에게 번호 대신 연결 존재만 알려 의도치 않은 연결 해제를 방지합니다.
        result.put("problemLinkHidden",manage && p.get("problem_id")!=null && publicProblemNumber(p,false)==null);
        var firstComments=commentPage(id,null,0,includeDeleted,user);
        result.put("comments",firstComments.get("items"));
        result.put("commentsTotal",firstComments.get("total"));
        result.put("commentsPage",0);
        return result;
    }
    /** 부모별 직접 자식만 페이지 단위로 내려 주므로 깊이 2 스레드도 잘리지 않고 단계별 확장할 수 있습니다. */
    public Map<String,Object> comments(long postId,Long parentId,int page,CustomUserDetails user) {
        return commentPage(postId,parentId,page,false,user);
    }
    public Map<String,Object> adminComments(long postId,Long parentId,int page,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        return commentPage(postId,parentId,page,true,user);
    }
    private Map<String,Object> commentPage(long postId,Long parentId,int page,boolean includeDeleted,
                                           CustomUserDetails user) {
        if(includeDeleted) ProblemService.requireOperator(user);
        var posts=db.queryForList("SELECT is_deleted FROM community_posts WHERE id=?",postId);
        if(posts.isEmpty() || (!includeDeleted && deleted(posts.getFirst().get("is_deleted"))))
            throw new ApiException(HttpStatus.NOT_FOUND);
        boolean postDeleted=deleted(posts.getFirst().get("is_deleted"));
        if(parentId!=null) {
            var parents=db.queryForList("SELECT post_id FROM community_comments WHERE id=?",parentId);
            if(parents.isEmpty() || ((Number)parents.getFirst().get("post_id")).longValue()!=postId)
                throw new ApiException(HttpStatus.NOT_FOUND);
        }
        int offset=offset(page);
        var rows=db.queryForList("""
            SELECT cc.*,(SELECT COUNT(*) FROM community_comments child WHERE child.parent_id=cc.id) AS reply_count
            FROM community_comments cc
            WHERE cc.post_id=? AND ((? IS NULL AND cc.parent_id IS NULL) OR cc.parent_id=?)
            ORDER BY cc.id LIMIT 20 OFFSET ?
            """,postId,parentId,parentId,offset);
        Long total=db.queryForObject("""
            SELECT COUNT(*) FROM community_comments cc
            WHERE cc.post_id=? AND ((? IS NULL AND cc.parent_id IS NULL) OR cc.parent_id=?)
            """,Long.class,postId,parentId,parentId);
        var items=rows.stream().map(c -> commentItem(c,includeDeleted,postDeleted,user)).toList();
        return Map.of("items",items,"total",total,"page",Math.max(page,0));
    }
    private static Map<String,Object> commentItem(Map<String,Object> c,boolean includeDeleted,
                                                  boolean postDeleted,CustomUserDetails user) {
        boolean removed=deleted(c.get("is_deleted"));
        boolean manage=mayManage(c,user);
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("id",c.get("id"));
        item.put("parentId",c.get("parent_id"));
        item.put("depth",c.get("depth"));
        item.put("body",removed && !includeDeleted?null:c.get("body"));
        item.put("deleted",removed);
        if(removed && !includeDeleted) item.put("deletedMessage","삭제된 댓글입니다.");
        item.put("authorUsername",c.get("author_username"));
        item.put("version",c.get("version"));
        item.put("edited",((Number)c.get("version")).longValue()>1);
        item.put("createdAt",isoUtc(c.get("created_at")));
        item.put("updatedAt",isoUtc(c.get("updated_at")));
        item.put("replyCount",c.get("reply_count"));
        item.put("canEdit",manage && !removed && !postDeleted);
        item.put("canDelete",manage && !removed && !postDeleted);
        return item;
    }
    private static Object publicProblemNumber(Map<String,Object> post,boolean includeDeleted) {
        if(includeDeleted || "PUBLISHED".equals(post.get("problem_status")) &&
            "PUBLIC".equals(post.get("problem_visibility")) && post.get("problem_group_id")==null)
            return post.get("problem_number");
        return null;
    }
    public Map<String,Object> adminPost(long id,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        return post(id,true,user);
    }
    public List<Map<String,Object>> postHistory(long id,int page,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        var rows=db.queryForList("""
            SELECT id,action,old_title AS oldTitle,old_category AS oldCategory,old_body AS oldBody,
                   old_problem_id AS oldProblemId,old_problem_number AS oldProblemNumber,
                   old_deleted AS oldDeleted,old_version AS oldVersion,new_title AS newTitle,
                   new_category AS newCategory,new_body AS newBody,
                   new_problem_id AS newProblemId,new_problem_number AS newProblemNumber,
                   new_deleted AS newDeleted,
                   new_version AS newVersion,editor_username AS editorUsername,edited_at AS editedAt,reason
            FROM community_post_revisions WHERE post_id=? ORDER BY id DESC LIMIT 20 OFFSET ?
            """,id,offset(page));
        return rows.stream().map(CommunityService::withUtcHistoryTime).toList();
    }
    public List<Map<String,Object>> commentHistory(long id,int page,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        var rows=db.queryForList("""
            SELECT id,action,old_body AS oldBody,old_deleted AS oldDeleted,old_version AS oldVersion,
                   new_body AS newBody,new_deleted AS newDeleted,new_version AS newVersion,
                   editor_username AS editorUsername,edited_at AS editedAt,reason
            FROM community_comment_revisions WHERE comment_id=? ORDER BY id DESC LIMIT 20 OFFSET ?
            """,id,offset(page));
        return rows.stream().map(CommunityService::withUtcHistoryTime).toList();
    }

    @Transactional
    public long createPost(CommunityRequests.PostWrite request,CustomUserDetails user) {
        ProblemService.requireUser(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        long uid=lockUser(user);
        var content=validatePost(request.title(),request.body(),request.category(),request.problemNumber(),
            null,user);
        db.update("""
            INSERT INTO community_posts(category,title,body,problem_id,author_id,author_username,created_at,updated_at)
            VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,content.category(),content.title(),content.body(),content.problemId(),uid,user.getUsername());
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        postRevision(id,"CREATE",null,content.title(),content.category(),content.body(),
            content.problemId(),content.problemNumber(),false,1,user,null);
        return id;
    }
    @Transactional
    public void updatePost(long id,CommunityRequests.PostUpdate request,CustomUserDetails user) {
        ProblemService.requireUser(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        lockUser(user);
        var old=postForUpdate(id);
        requireManage(old,user);
        if("NOTICE".equals(old.get("category")) && !ProblemService.operator(user))
            throw new ApiException(HttpStatus.FORBIDDEN);
        requireVersion(old,request.version());
        if(deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제된 글입니다.");
        var content=validatePost(request.title(),request.body(),request.category(),request.problemNumber(),
            (String)old.get("category"),user);
        if(Boolean.TRUE.equals(request.preserveProblemLink()) && request.problemNumber()==null) {
            // 기존 연결은 권한을 통과한 자신의 글에서만 유지하며 비공개 문제를 새로 연결하지 않습니다.
            content=new PostContent(content.title(),content.body(),content.category(),
                nullableLong(old.get("problem_id")),nullableInteger(old.get("problem_number")));
        }
        long next=request.version()+1;
        changed(db.update("""
            UPDATE community_posts SET title=?,body=?,category=?,problem_id=?,version=?,
                updated_at=UTC_TIMESTAMP(6) WHERE id=? AND version=?
            """,content.title(),content.body(),content.category(),content.problemId(),next,id,request.version()));
        postRevision(id,"UPDATE",old,content.title(),content.category(),content.body(),
            content.problemId(),content.problemNumber(),false,next,user,null);
    }
    @Transactional
    public void deletePost(long id,long version,CustomUserDetails user) {
        lockUser(user);
        var old=postForUpdate(id);
        requireManage(old,user);
        if("NOTICE".equals(old.get("category")) && !ProblemService.operator(user))
            throw new ApiException(HttpStatus.FORBIDDEN);
        requireVersion(old,version);
        if(deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"이미 삭제된 글입니다.");
        long next=version+1;
        changed(db.update("""
            UPDATE community_posts SET is_deleted=TRUE,deleted_at=UTC_TIMESTAMP(6),version=?,
                updated_at=UTC_TIMESTAMP(6) WHERE id=? AND version=?
            """,next,id,version));
        postRevision(id,"DELETE",old,(String)old.get("title"),(String)old.get("category"),
            (String)old.get("body"),nullableLong(old.get("problem_id")),
            nullableInteger(old.get("problem_number")),true,next,user,null);
    }
    @Transactional
    public void restorePost(long id,CommunityRequests.Restore request,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        lockUser(user);
        var old=postForUpdate(id);
        requireVersion(old,request.version());
        if(!deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제되지 않은 글입니다.");
        long next=request.version()+1;
        changed(db.update("""
            UPDATE community_posts SET is_deleted=FALSE,deleted_at=NULL,version=?,
                updated_at=UTC_TIMESTAMP(6) WHERE id=? AND version=?
            """,next,id,request.version()));
        postRevision(id,"RESTORE",old,(String)old.get("title"),(String)old.get("category"),
            (String)old.get("body"),nullableLong(old.get("problem_id")),
            nullableInteger(old.get("problem_number")),false,next,user,reason(request.reason()));
    }

    @Transactional
    public long createComment(long postId,CommunityRequests.CommentWrite request,CustomUserDetails user) {
        ProblemService.requireUser(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        long uid=lockUser(user);
        String body=validateBody(request.body(),5000);
        var post=postForUpdate(postId);
        if(deleted(post.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제된 글에는 댓글을 달 수 없습니다.");
        int depth=0;
        if(request.parentId()!=null) {
            var parent=commentForUpdate(request.parentId());
            if(((Number)parent.get("post_id")).longValue()!=postId || deleted(parent.get("is_deleted")))
                throw new ApiException(HttpStatus.BAD_REQUEST,"답글 대상을 확인해주세요.");
            depth=((Number)parent.get("depth")).intValue()+1;
            if(depth>2) throw new ApiException(HttpStatus.BAD_REQUEST,"답글은 두 단계까지만 작성할 수 있습니다.");
        }
        db.update("""
            INSERT INTO community_comments(post_id,parent_id,depth,body,author_id,author_username,created_at,updated_at)
            VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,postId,request.parentId(),depth,body,uid,user.getUsername());
        long id=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        commentRevision(id,"CREATE",null,body,false,1,user,null);
        return id;
    }
    @Transactional
    public void updateComment(long id,CommunityRequests.CommentUpdate request,CustomUserDetails user) {
        ProblemService.requireUser(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        lockUser(user);
        var old=lockedCommentWithPost(id);
        requireManage(old,user);
        requireVersion(old,request.version());
        if(deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제된 댓글입니다.");
        String body=validateBody(request.body(),5000);
        long next=request.version()+1;
        changed(db.update("""
            UPDATE community_comments SET body=?,version=?,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND version=?
            """,body,next,id,request.version()));
        commentRevision(id,"UPDATE",old,body,false,next,user,null);
    }
    @Transactional
    public void deleteComment(long id,long version,CustomUserDetails user) {
        lockUser(user);
        var old=lockedCommentWithPost(id);
        requireManage(old,user);
        requireVersion(old,version);
        if(deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"이미 삭제된 댓글입니다.");
        long next=version+1;
        changed(db.update("""
            UPDATE community_comments SET is_deleted=TRUE,deleted_at=UTC_TIMESTAMP(6),version=?,
                updated_at=UTC_TIMESTAMP(6) WHERE id=? AND version=?
            """,next,id,version));
        commentRevision(id,"DELETE",old,(String)old.get("body"),true,next,user,null);
    }
    @Transactional
    public void restoreComment(long id,CommunityRequests.Restore request,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if(request==null) throw new ApiException(HttpStatus.BAD_REQUEST);
        lockUser(user);
        var old=commentForUpdate(id);
        requireVersion(old,request.version());
        if(!deleted(old.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제되지 않은 댓글입니다.");
        long next=request.version()+1;
        changed(db.update("""
            UPDATE community_comments SET is_deleted=FALSE,deleted_at=NULL,version=?,
                updated_at=UTC_TIMESTAMP(6) WHERE id=? AND version=?
            """,next,id,request.version()));
        commentRevision(id,"RESTORE",old,(String)old.get("body"),false,next,user,reason(request.reason()));
    }

    private PostContent validatePost(String title,String body,String category,Integer number,
                                     String priorCategory,CustomUserDetails user) {
        String cleanTitle=title==null?"":title.strip().replaceAll("(?U)\\s+"," ");
        if(cleanTitle.isEmpty() || cleanTitle.length()>200 || category==null || !CATEGORIES.contains(category))
            throw new ApiException(HttpStatus.BAD_REQUEST,"게시글 제목 또는 분류를 확인해주세요.");
        String cleanBody=validateBody(body,20000);
        if(("NOTICE".equals(category) || "NOTICE".equals(priorCategory)) && !ProblemService.operator(user))
            throw new ApiException(HttpStatus.FORBIDDEN,"공지는 운영자만 작성하거나 변경할 수 있습니다.");
        Long problemId=null;
        if(number!=null) {
            var found=db.queryForList("""
                SELECT id FROM problems WHERE problem_number=? AND group_id IS NULL
                    AND status='PUBLISHED' AND visibility='PUBLIC'
                FOR SHARE
                """,number);
            if(found.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"공개 일반문제만 연결할 수 있습니다.");
            problemId=((Number)found.getFirst().get("id")).longValue();
        }
        return new PostContent(cleanTitle,cleanBody,category,problemId,number);
    }
    private static String validateBody(String body,int maximum) {
        if(body==null || body.isBlank() || body.length()>maximum)
            throw new ApiException(HttpStatus.BAD_REQUEST,"본문 길이를 확인해주세요.");
        return body;
    }
    private Map<String,Object> postForUpdate(long id) {
        var rows=db.queryForList("SELECT * FROM community_posts WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        var post=rows.getFirst();
        if(post.get("problem_id")!=null) post.put("problem_number",db.queryForObject(
            "SELECT problem_number FROM problems WHERE id=?",Integer.class,post.get("problem_id")));
        return post;
    }
    private Map<String,Object> commentForUpdate(long id) {
        var rows=db.queryForList("SELECT * FROM community_comments WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        return rows.getFirst();
    }
    private Map<String,Object> lockedCommentWithPost(long id) {
        var rows=db.queryForList("SELECT post_id FROM community_comments WHERE id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        long postId=((Number)rows.getFirst().get("post_id")).longValue();
        var post=postForUpdate(postId);
        if(deleted(post.get("is_deleted"))) throw new ApiException(HttpStatus.CONFLICT,"삭제된 글입니다.");
        return commentForUpdate(id);
    }
    private void postRevision(long id,String action,Map<String,Object> old,String title,String category,
                              String body,Long problemId,Integer problemNumber,boolean removed,long version,
                              CustomUserDetails user,String reason) {
        db.update("""
            INSERT INTO community_post_revisions(post_id,action,old_title,old_category,old_body,
                old_problem_id,old_problem_number,old_deleted,old_version,new_title,new_category,new_body,
                new_problem_id,new_problem_number,new_deleted,new_version,editor_id,editor_username,reason,edited_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
            """,id,action,old==null?null:old.get("title"),old==null?null:old.get("category"),
            old==null?null:old.get("body"),old==null?null:old.get("problem_id"),
            old==null?null:old.get("problem_number"),old==null?null:old.get("is_deleted"),
            old==null?null:old.get("version"),title,category,body,problemId,problemNumber,
            removed,version,user.getUserId(),user.getUsername(),reason);
    }
    private void commentRevision(long id,String action,Map<String,Object> old,String body,boolean removed,
                                 long version,CustomUserDetails user,String reason) {
        db.update("""
            INSERT INTO community_comment_revisions(comment_id,action,old_body,old_deleted,old_version,
                new_body,new_deleted,new_version,editor_id,editor_username,reason,edited_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
            """,id,action,old==null?null:old.get("body"),old==null?null:old.get("is_deleted"),
            old==null?null:old.get("version"),body,removed,version,user.getUserId(),user.getUsername(),reason);
    }
    private static void requireVersion(Map<String,Object> row,Long version) {
        if(version==null || version<1 || version==Long.MAX_VALUE || ((Number)row.get("version")).longValue()!=version)
            throw new ApiException(HttpStatus.CONFLICT,"버전이 달라 새로고침 후 다시 시도해주세요.");
    }
    private static void changed(int count) {
        if(count!=1) throw new ApiException(HttpStatus.CONFLICT,"버전이 달라 새로고침 후 다시 시도해주세요.");
    }
    private static boolean mayManage(Map<String,Object> row,CustomUserDetails user) {
        return user!=null && user.isEnabled() && (ProblemService.operator(user) ||
            row.get("author_id")!=null && ((Number)row.get("author_id")).longValue()==user.getUserId());
    }
    private static void requireManage(Map<String,Object> row,CustomUserDetails user) {
        if(!mayManage(row,user)) throw new ApiException(HttpStatus.FORBIDDEN);
    }
    private static boolean deleted(Object value) {
        return value instanceof Boolean b?b:value instanceof Number n && n.intValue()!=0;
    }
    private static Long nullableLong(Object value) { return value==null?null:((Number)value).longValue(); }
    private static Integer nullableInteger(Object value) { return value==null?null:((Number)value).intValue(); }
    private static String isoUtc(Object value) {
        if(value==null) return null;
        LocalDateTime utc=value instanceof Timestamp stamp?stamp.toLocalDateTime()
            : value instanceof LocalDateTime local?local:null;
        if(utc==null) throw new IllegalStateException("Unexpected community datetime value");
        return utc.toInstant(ZoneOffset.UTC).toString();
    }
    private static Map<String,Object> withUtcListTimes(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value) -> {
            if(key.equalsIgnoreCase("createdAt")) result.put("createdAt",isoUtc(value));
            else if(key.equalsIgnoreCase("updatedAt")) result.put("updatedAt",isoUtc(value));
            else result.put(key,value);
        });
        return result;
    }
    private static Map<String,Object> withUtcHistoryTime(Map<String,Object> row) {
        Map<String,Object> result=new LinkedHashMap<>();
        row.forEach((key,value) -> {
            if(key.equalsIgnoreCase("editedAt")) result.put("editedAt",isoUtc(value));
            else result.put(key,value);
        });
        return result;
    }
    private static String reason(String value) {
        if(value==null || value.isBlank()) return "관리자 복원";
        if(value.length()>2000) throw new ApiException(HttpStatus.BAD_REQUEST,"사유가 너무 깁니다.");
        return value.strip();
    }
    private static int offset(int page) { return Math.min(Math.max(page,0),10000)*20; }
    private record PostContent(String title,String body,String category,Long problemId,Integer problemNumber) {}
}
