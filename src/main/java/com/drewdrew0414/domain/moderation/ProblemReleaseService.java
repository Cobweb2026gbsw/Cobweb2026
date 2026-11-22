package com.drewdrew0414.domain.moderation;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service @RequiredArgsConstructor
public class ProblemReleaseService {
    public record Request(@NotEmpty @Size(max=100) List<@Positive @NotNull Integer> problemNumbers,
                          @Future @NotNull OffsetDateTime scheduledFor) {}
    private final JdbcTemplate db;
    private final ProblemService problems;

    @Transactional
    public long schedule(Request request,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        var numbers=request.problemNumbers().stream().distinct().sorted().toList();
        if (numbers.size()!=request.problemNumbers().size()) throw new ApiException(HttpStatus.BAD_REQUEST,"중복된 문제 번호가 있습니다.");
        long actor=user.getUserId();
        // 다른 쓰기 경로와 동일하게 사용자 다음 문제 번호 순서로 잠급니다.
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",actor);
        var ids=db.queryForList("""
            INSERT INTO problem_release_batches(scheduled_for,created_by) SELECT ?,? WHERE CAST(? AS timestamptz)>clock_timestamp()
            RETURNING id
            """,Long.class,request.scheduledFor(),actor,request.scheduledFor());
        if (ids.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"미래의 공개 시각을 선택하세요.");
        long batch=ids.getFirst();
        for (int number:numbers) {
            var rows=db.queryForList("SELECT * FROM problems WHERE problem_number=? FOR UPDATE",number);
            if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"문제 #"+number+"을 찾을 수 없습니다.");
            var p=rows.getFirst();
            if (p.get("group_id")!=null || !"PRIVATE".equals(p.get("visibility")) ||
                !("PUBLISHED".equals(p.get("status")) || "PENDING_REVIEW".equals(p.get("status")) && "PUBLIC".equals(p.get("requested_visibility"))))
                throw new ApiException(HttpStatus.CONFLICT,"#"+number+"은 검수 대기(공개 요청) 또는 검수 완료된 비공개 일반 문제여야 합니다.");
            if(Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM problem_release_items i JOIN problem_release_batches b ON b.id=i.batch_id
                  WHERE i.problem_id=? AND b.status='PENDING')
                """,Boolean.class,p.get("id")))) throw new ApiException(HttpStatus.CONFLICT,"#"+number+"은 이미 예약되었습니다.");
            problems.requireReadyForPublication(p);
            long revision=((Number)p.get("publication_revision")).longValue();
            if ("PENDING_REVIEW".equals(p.get("status"))) {
                db.update("UPDATE problems SET status='PUBLISHED',publication_revision=publication_revision+1 WHERE id=?",p.get("id"));
                db.update("""
                    INSERT INTO problem_status_history(problem_id,status,cause,checker_id)
                    VALUES(?,'PUBLISHED','검수 승인 · 예약 공개 대기',?)
                    """,p.get("id"),actor);
                revision++;
            }
            db.update("INSERT INTO problem_release_items(batch_id,problem_id,publication_revision) VALUES(?,?,?)",batch,p.get("id"),revision);
        }
        return batch;
    }

    public List<Map<String,Object>> list(int page,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if(page<0) throw new ApiException(HttpStatus.BAD_REQUEST);
        return db.queryForList("""
            SELECT b.id,b.status,b.failure_reason AS "failureReason",to_jsonb(b.scheduled_for) #>> '{}' AS "scheduledFor",
              u.username AS creator, string_agg(p.problem_number::text, ', ' ORDER BY p.problem_number) AS numbers
            FROM problem_release_batches b JOIN users u ON u.id=b.created_by
            JOIN problem_release_items i ON i.batch_id=b.id JOIN problems p ON p.id=i.problem_id
            GROUP BY b.id,u.username ORDER BY b.id DESC LIMIT 30 OFFSET ?
            """,(long)page*30);
    }

    public void cancel(long id,CustomUserDetails user) {
        ProblemService.requireOperator(user);
        if(db.update("""
            UPDATE problem_release_batches SET status='CANCELLED',finished_at=clock_timestamp(),cancelled_by=?
            WHERE id=? AND status='PENDING'
            """,user.getUserId(),id)==0) throw new ApiException(HttpStatus.CONFLICT,"대기 중인 예약을 찾을 수 없습니다.");
    }

    /** 한 트랜잭션은 한 묶음만 처리합니다. 여러 웹 인스턴스의 스케줄러가 같은 묶음을 가져가지 않습니다. */
    @Transactional
    public boolean runNextDue() {
        var batches=db.queryForList("""
            SELECT id,created_by,scheduled_for FROM problem_release_batches
            WHERE status='PENDING' AND scheduled_for<=clock_timestamp() ORDER BY scheduled_for,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """);
        if (batches.isEmpty()) return false;
        var batch=batches.getFirst();
        long id=((Number)batch.get("id")).longValue();
        var actors=db.queryForList("SELECT role,status FROM users WHERE id=? FOR UPDATE",batch.get("created_by"));
        if(actors.isEmpty() || !"ACTIVE".equals(actors.getFirst().get("status")) || "USER".equals(actors.getFirst().get("role"))) {
            fail(id,"예약 등록자의 운영 권한이 변경되었습니다.");
            return true;
        }
        var items=db.queryForList("""
            SELECT p.*,i.publication_revision AS approved_revision FROM problem_release_items i JOIN problems p ON p.id=i.problem_id
            WHERE i.batch_id=? ORDER BY p.problem_number FOR UPDATE OF p
            """,id);
        for(var p:items) {
            if(!p.get("publication_revision").equals(p.get("approved_revision"))
                || p.get("group_id")!=null || !"PRIVATE".equals(p.get("visibility")) || !"PUBLISHED".equals(p.get("status"))) {
                fail(id,"#"+p.get("problem_number")+"의 검수 판본 또는 공개 상태가 변경되었습니다.");
                return true;
            }
            try {
                problems.requireReadyForPublication(p);
            } catch(ApiException e) {
                fail(id,"#"+p.get("problem_number")+"의 공개 요건을 다시 확인해주세요.");
                return true;
            }
        }
        db.update("""
            UPDATE problems p SET visibility='PUBLIC',requested_visibility='PUBLIC',publication_revision=p.publication_revision+1,
                published_at=b.scheduled_for AT TIME ZONE 'UTC'
            FROM problem_release_items i JOIN problem_release_batches b ON b.id=i.batch_id
            WHERE p.id=i.problem_id AND b.id=?
            """,id);
        db.update("""
            INSERT INTO problem_status_history(problem_id,status,cause,checker_id)
            SELECT problem_id,'PUBLISHED','예약 공개',? FROM problem_release_items WHERE batch_id=?
            """,batch.get("created_by"),id);
        db.update("UPDATE problem_release_batches SET status='PUBLISHED',finished_at=clock_timestamp() WHERE id=?",id);
        return true;
    }
    private void fail(long id,String reason) {
        db.update("UPDATE problem_release_batches SET status='FAILED',failure_reason=?,finished_at=clock_timestamp() WHERE id=?",reason,id);
    }
}
