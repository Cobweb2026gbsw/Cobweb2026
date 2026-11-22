package com.drewdrew0414.domain.moderation;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupPermission;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.user.repository.UserRepository;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service @RequiredArgsConstructor
public class ProblemIssueService {
    public enum Category { PROBLEM_ERROR, JUDGING_APPEAL }
    public enum Status { OPEN, IN_PROGRESS, RESOLVED }
    public record NewIssue(@NotNull @Positive Integer problemNumber, @Positive Long submissionId,
                           @NotNull Category category, @NotBlank @Size(max=2000) String body) {}
    public record Review(@Positive int version,@NotNull Status status,@Size(max=2000) String response,
                         @Size(max=16) String assignee) {}

    private final JdbcTemplate db;
    private final ProblemService problems;
    private final GroupService groups;
    private final UserRepository users;
    private final ContestService contests;

    // 일반 접수함에 시험 문제나 답안을 노출하지 않습니다. 시험 접수함은 매 요청마다 현재 권한을 재검사합니다.
    private void access(Long groupId,Long contestId,boolean staff,CustomUserDetails user) {
        long uid=ProblemService.requireUser(user);
        if ((groupId==null)!=(contestId==null)) throw new ApiException(HttpStatus.NOT_FOUND);
        if (contestId==null) {
            if (staff) ProblemService.requireOperator(user);
            return;
        }
        groups.requireMember(groupId,user);
        if (!Boolean.TRUE.equals(db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM group_contests WHERE group_id=? AND id=?)",Boolean.class,groupId,contestId)))
            throw new ApiException(HttpStatus.NOT_FOUND);
        if (staff) groups.requirePermission(groupId,user,GroupPermission.VIEW_CONTEST_RESULTS);
        else contests.requireSubmissionRead(contestId,uid,user);
    }

    @Transactional
    public long create(Long groupId,Long contestId,NewIssue request,CustomUserDetails user) {
        access(groupId,contestId,false,user);
        long uid=user.getUserId();
        if ((request.category()==Category.JUDGING_APPEAL)!=(request.submissionId()!=null))
            throw new ApiException(HttpStatus.BAD_REQUEST,"채점 이의제기는 본인 제출 번호가 필요합니다.");
        long pid;
        if (contestId==null) {
            pid=((Number)problems.load(request.problemNumber(),user).get("id")).longValue();
            if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM contest_problems WHERE problem_id=?)",
                Boolean.class,pid))) throw new ApiException(HttpStatus.NOT_FOUND);
        } else {
            var ids=db.queryForList("""
                SELECT p.id FROM problems p JOIN contest_problems cp ON cp.problem_id=p.id
                WHERE cp.contest_id=? AND p.group_id=? AND p.problem_number=? AND p.visibility='GROUP'
                """,Long.class,contestId,groupId,request.problemNumber());
            if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
            pid=ids.getFirst();
        }
        if (request.submissionId()!=null && !Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM submissions WHERE id=? AND user_id=? AND problem_id=?
                AND contest_id IS NOT DISTINCT FROM CAST(? AS bigint))
            """,Boolean.class,request.submissionId(),uid,pid,contestId))) throw new ApiException(HttpStatus.NOT_FOUND);
        return db.queryForObject("""
            INSERT INTO problem_issues(reporter_id,problem_id,submission_id,contest_id,category,body)
            VALUES(?,?,?,?,?,?) RETURNING id
            """,Long.class,uid,pid,request.submissionId(),contestId,request.category().name(),request.body().strip());
    }

    public List<Map<String,Object>> list(Long groupId,Long contestId,boolean staff,Status status,int page,CustomUserDetails user) {
        access(groupId,contestId,staff,user);
        if (page<0) throw new ApiException(HttpStatus.BAD_REQUEST);
        String filter=status==null?"":status.name();
        // 일반 접수 내역이 나중에 시험에 편입되면 일반 화면에서도 숨깁니다.
        return db.queryForList("""
            SELECT i.id, p.problem_number AS "problemNumber", i.submission_id AS "submissionId",
                i.category,i.body,i.status,i.response,i.version,writer.username AS reporter, owner.username AS assignee,
                to_jsonb(i.updated_at) #>> '{}' AS "updatedAt"
            FROM problem_issues i JOIN problems p ON p.id=i.problem_id JOIN users writer ON writer.id=i.reporter_id
            LEFT JOIN users owner ON owner.id=i.assignee_id
            WHERE i.contest_id IS NOT DISTINCT FROM CAST(? AS bigint) AND (? OR i.reporter_id=?)
              AND (i.contest_id IS NOT NULL OR NOT EXISTS(SELECT 1 FROM contest_problems cp WHERE cp.problem_id=p.id))
              AND (i.contest_id IS NOT NULL OR ? OR p.author_id=? OR
                   p.status='PUBLISHED' AND p.group_id IS NULL AND p.visibility='PUBLIC' OR EXISTS (
                     SELECT 1 FROM group_members m JOIN group_roles r ON r.id=m.role_id AND r.group_id=m.group_id
                     JOIN study_groups g ON g.id=m.group_id AND g.status='ACCEPTED'
                     WHERE m.user_id=? AND m.group_id=p.group_id AND
                       (r.can_edit_problems OR r.can_review_problems OR (r.can_view_problems AND p.status='PUBLISHED' AND p.visibility='GROUP'))))
              AND (?='' OR i.status=?)
            ORDER BY i.id DESC LIMIT 30 OFFSET ?
            """,contestId,staff,user.getUserId(),ProblemService.operator(user),user.getUserId(),user.getUserId(),filter,filter,(long)page*30);
    }

    @Transactional
    public void review(long id,Long groupId,Long contestId,Review input,CustomUserDetails user) {
        access(groupId,contestId,true,user);
        String response=input.response()==null?"":input.response().strip();
        if (input.status()==Status.RESOLVED && response.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST,"처리 완료 전 답변을 입력해주세요.");
        Long assignee=null;
        if(input.assignee()!=null && !input.assignee().isBlank()) {
            var person=users.findByUsername(input.assignee().strip()).orElseThrow(
                ()->new ApiException(HttpStatus.BAD_REQUEST,"담당 가능한 운영자 아이디를 입력해주세요."));
            var reviewer=new CustomUserDetails(person);
            boolean eligible=reviewer.isEnabled() && (contestId==null ? ProblemService.operator(reviewer)
                : groups.can(groupId,reviewer,GroupPermission.VIEW_CONTEST_RESULTS));
            if(!eligible) throw new ApiException(HttpStatus.BAD_REQUEST,"담당 가능한 운영자 아이디를 입력해주세요.");
            assignee=person.getId();
        }
        var versions=db.queryForList("""
            SELECT i.version FROM problem_issues i WHERE i.id=? AND i.contest_id IS NOT DISTINCT FROM CAST(? AS bigint)
              AND (i.contest_id IS NOT NULL OR NOT EXISTS(SELECT 1 FROM contest_problems cp WHERE cp.problem_id=i.problem_id))
            FOR UPDATE OF i
            """,Integer.class,id,contestId);
        if (versions.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        if (versions.getFirst()!=input.version()) throw new ApiException(HttpStatus.CONFLICT,"다른 담당자가 먼저 수정했습니다. 새로고침해주세요.");
        db.update("""
            UPDATE problem_issues SET status=?,response=?,updated_by=?,updated_at=clock_timestamp(),version=version+1,
                assignee_id=? WHERE id=?
            """,input.status().name(),response,user.getUserId(),assignee,id);
    }
}
