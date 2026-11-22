package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContestCommunicationService {
    private final ContestService contests;
    private final GroupService groups;
    private final JdbcTemplate db;

    public record Question(long id, Integer problemNumber, String asker, String body, String createdAt,
                           String answer, String answeredAt) {}
    public record Notice(long id, String body, String createdAt) {}
    public record Messages(List<Question> questions, List<Notice> announcements, boolean hasMore) {}

    @Transactional(readOnly = true)
    public Messages messages(long gid, long cid, Long beforeId, CustomUserDetails user) {
        long uid = GroupService.requireUser(user);
        var contest = contests.contest(gid, cid, false);
        boolean manager = groups.can(gid, user, GroupPermission.MANAGE_CONTESTS);
        if (!manager && !contests.personalReadAllowed(contest, user, contests.now()))
            throw new ApiException(HttpStatus.FORBIDDEN, "현재 시험 질문을 볼 수 없습니다.");
        long before = beforeId == null ? Long.MAX_VALUE : beforeId;
        var questions = db.query("""
                SELECT q.id, p.problem_number, u.username, q.question, q.created_at, q.answer, q.answered_at
                FROM contest_clarifications q JOIN users u ON u.id=q.asker_id
                LEFT JOIN problems p ON p.id=q.problem_id
                WHERE q.contest_id=? AND (? OR q.asker_id=?) AND q.id<? ORDER BY q.id DESC LIMIT 31
                """, (rs,n) -> new Question(rs.getLong("id"), rs.getObject("problem_number",Integer.class),
                    rs.getString("username"), rs.getString("question"), ContestService.iso(rs.getTimestamp("created_at")),
                    rs.getString("answer"), rs.getTimestamp("answered_at")==null ? null : ContestService.iso(rs.getTimestamp("answered_at"))),
                cid, manager, uid, before);
        var notices = db.query("""
                SELECT id,body,created_at FROM contest_announcements
                WHERE contest_id=? ORDER BY id DESC LIMIT 30
                """, (rs,n) -> new Notice(rs.getLong("id"),rs.getString("body"),ContestService.iso(rs.getTimestamp("created_at"))),cid);
        return new Messages(questions.stream().limit(30).toList(), notices, questions.size()>30);
    }

    @Transactional
    public long ask(long gid, long cid, Integer problemNumber, String body, CustomUserDetails user) {
        long uid = GroupService.requireUser(user);
        var contest = contests.contest(gid, cid, true);
        contests.requireActiveTake(contest, user);
        Long problemId = null;
        if (problemNumber != null) {
            problemId = db.query("""
                    SELECT cp.problem_id FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
                    WHERE cp.contest_id=? AND p.problem_number=?
                    """, rs -> rs.next() ? rs.getLong(1) : null, cid, problemNumber);
            if (problemId == null) throw new ApiException(HttpStatus.NOT_FOUND, "이 시험에 포함된 문제를 선택해 주세요.");
        }
        return db.queryForObject("""
                INSERT INTO contest_clarifications(contest_id,problem_id,asker_id,question)
                VALUES(?,?,?,?) RETURNING id
                """,Long.class,cid,problemId,uid,body.strip());
    }

    @Transactional
    public void answer(long gid, long cid, long questionId, String body, boolean publish, CustomUserDetails user) {
        requireActiveManager(gid,cid,user);
        var ids = db.queryForList("SELECT id FROM contest_clarifications WHERE contest_id=? AND id=?",Long.class,cid,questionId);
        if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"질문을 찾을 수 없습니다.");
        int saved = db.update("""
                UPDATE contest_clarifications SET answer=?, answered_by=?, answered_at=(clock_timestamp() AT TIME ZONE 'UTC')
                WHERE contest_id=? AND id=? AND answer IS NULL
                """, body.strip(),user.getUserId(),cid,questionId);
        if (saved == 0) throw new ApiException(HttpStatus.CONFLICT,"이미 답변된 질문입니다.");
        if (publish) insertNotice(cid,body,user);
    }

    @Transactional
    public long announce(long gid, long cid, String body, CustomUserDetails user) {
        requireActiveManager(gid,cid,user);
        return insertNotice(cid,body,user);
    }

    private long insertNotice(long cid,String body,CustomUserDetails user) {
        // 공개되는 것은 운영자가 쓴 공지 내용뿐입니다. 질문 내용과 질문자 ID는 복사하지 않습니다.
        return db.queryForObject("INSERT INTO contest_announcements(contest_id,body,author_id) VALUES(?,?,?) RETURNING id",
                Long.class,cid,body.strip(),user.getUserId());
    }

    private void requireActiveManager(long gid,long cid,CustomUserDetails user) {
        groups.requirePermission(gid,user,GroupPermission.MANAGE_CONTESTS);
        var contest = contests.contest(gid,cid,true);
        if (!"ACTIVE".equals(ContestService.status(contest,contests.now())))
            throw new ApiException(HttpStatus.CONFLICT,"진행 중인 시험에서 공지와 답변을 작성할 수 있습니다.");
    }
}
