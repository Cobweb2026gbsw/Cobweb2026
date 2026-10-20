package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 서버 UTC 시각을 기준으로 시험 문제와 제출·결과를 분리합니다. */
@Service @RequiredArgsConstructor
public class ContestService {
    private final JdbcTemplate db;
    private final GroupService groups;

    private static long id(Object value) { return ((Number)value).longValue(); }
    private static boolean enabled(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0;
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private static LocalDateTime local(Object value) {
        if(value instanceof Timestamp t) return t.toLocalDateTime();
        if(value instanceof LocalDateTime t) return t;
        throw new IllegalStateException("Unexpected UTC datetime value");
    }
    private static String iso(Object value) { return local(value).toInstant(ZoneOffset.UTC).toString(); }
    private LocalDateTime now() { return local(db.queryForObject("SELECT UTC_TIMESTAMP(6)",Timestamp.class)); }
    private static String status(Map<String,Object> contest,LocalDateTime now) {
        if(now.isBefore(local(contest.get("starts_at")))) return "UPCOMING";
        return now.isBefore(local(contest.get("ends_at")))?"ACTIVE":"ENDED";
    }
    private Map<String,Object> contest(long gid,long cid,boolean lock) {
        var rows=db.queryForList("SELECT * FROM group_contests WHERE group_id=? AND id=?"+(lock?" FOR UPDATE":""),gid,cid);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"코딩테스트를 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private Map<String,Object> summary(Map<String,Object> row,LocalDateTime now) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",row.get("id")); result.put("title",row.get("title"));
        result.put("startsAt",iso(row.get("starts_at"))); result.put("endsAt",iso(row.get("ends_at")));
        result.put("status",status(row,now)); result.put("replayAllowed",enabled(row.get("replay_allowed")));
        result.put("captureProtection",enabled(row.get("capture_protection")));
        result.put("windowSwitchProtection",enabled(row.get("window_switch_protection")));
        result.put("problemCount",db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE contest_id=?",Long.class,row.get("id")));
        return result;
    }
    private boolean staff(long gid,CustomUserDetails user) {
        return groups.can(gid,user,GroupPermission.MANAGE_CONTESTS) ||
            groups.can(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
    }
    private boolean questionStaff(long gid,CustomUserDetails user) {
        return staff(gid,user) || groups.can(gid,user,GroupPermission.EDIT_PROBLEMS) ||
            groups.can(gid,user,GroupPermission.REVIEW_PROBLEMS);
    }
    private boolean participated(long cid,long uid) {
        return db.queryForObject("SELECT COUNT(*) FROM submissions WHERE contest_id=? AND user_id=?",Long.class,cid,uid)>0;
    }
    private boolean canReadQuestions(Map<String,Object> contest,CustomUserDetails user,LocalDateTime now) {
        long gid=id(contest.get("group_id"));
        if(questionStaff(gid,user)) return true;
        return personalReadAllowed(contest,user,now);
    }
    private boolean personalReadAllowed(Map<String,Object> contest,CustomUserDetails user,LocalDateTime now) {
        long gid=id(contest.get("group_id"));
        if(!groups.can(gid,user,GroupPermission.TAKE_CONTESTS)) return false;
        String state=status(contest,now);
        return "ACTIVE".equals(state) || ("ENDED".equals(state) &&
            enabled(contest.get("replay_allowed")) && participated(id(contest.get("id")),user.getUserId()));
    }
    private void requireQuestionAccess(Map<String,Object> contest,CustomUserDetails user,long problemId) {
        GroupService.requireUser(user);
        var authors=db.queryForList("SELECT author_id FROM problems WHERE id=?",problemId);
        if(!authors.isEmpty() && id(authors.getFirst().get("author_id"))==user.getUserId()) return;
        if(!canReadQuestions(contest,user,now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 시험 문제를 볼 수 없습니다.");
    }
    private void requireActiveTake(Map<String,Object> contest,CustomUserDetails user) {
        GroupService.requireUser(user);
        if(!groups.can(id(contest.get("group_id")),user,GroupPermission.TAKE_CONTESTS) ||
            !"ACTIVE".equals(status(contest,now())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"시험 응시 시간 또는 권한이 아닙니다.");
    }

    public List<Map<String,Object>> list(long gid,CustomUserDetails user) {
        groups.requireMember(gid,user);
        var rows=db.queryForList("SELECT * FROM group_contests WHERE group_id=? ORDER BY starts_at DESC,id DESC LIMIT 100",gid);
        LocalDateTime now=now();
        return rows.stream().map(r -> summary(r,now)).toList();
    }
    public Map<String,Object> detail(long gid,long cid,CustomUserDetails user) {
        groups.requireMember(gid,user);
        var row=contest(gid,cid,false);
        var result=summary(row,now());
        result.put("canManage",groups.can(gid,user,GroupPermission.MANAGE_CONTESTS));
        result.put("canViewResults",groups.can(gid,user,GroupPermission.VIEW_CONTEST_RESULTS));
        result.put("problems",visibleProblemSummaries(row,user));
        return result;
    }
    private List<Map<String,Object>> visibleProblemSummaries(Map<String,Object> contest,CustomUserDetails user) {
        var rows=db.queryForList("""
            SELECT p.id,p.problem_number,p.problem_title,p.author_id
            FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
            WHERE cp.contest_id=? ORDER BY cp.display_order
            """,contest.get("id"));
        if(!canReadQuestions(contest,user,now()))
            rows=rows.stream().filter(p -> user!=null && id(p.get("author_id"))==user.getUserId()).toList();
        return rows.stream().map(p -> Map.<String,Object>of(
            "problemNumber",p.get("problem_number"),"problemTitle",p.get("problem_title"))).toList();
    }
    @Transactional
    public long create(long gid,ContestRequests.Write request,CustomUserDetails user) {
        long uid=GroupService.requireUser(user);
        // created_by FK의 사용자 부모 행을 그룹보다 먼저 잠가 그룹 생성/가입과 순서를 맞춥니다.
        if(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(db.queryForList("SELECT id FROM study_groups WHERE id=? AND status='ACCEPTED' FOR UPDATE",gid).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"그룹을 찾을 수 없습니다.");
        groups.requirePermission(gid,user,GroupPermission.CREATE_CONTESTS);
        validateWrite(request,true,null);
        db.update("""
            INSERT INTO group_contests(group_id,title,starts_at,ends_at,replay_allowed,
              capture_protection,window_switch_protection,created_by)
            VALUES(?,?,?,?,?,?,?,?)
            """,gid,request.title().trim(),utc(request.startsAt()),utc(request.endsAt()),
            request.replayAllowed(),request.captureProtection(),request.windowSwitchProtection(),uid);
        long cid=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        var problemIds=lockProblems(gid,request.problemNumbers());
        insertProblems(cid,request.problemNumbers(),problemIds);
        if(!utc(request.startsAt()).isAfter(now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"시험 시작 시각이 지나 다시 저장해야 합니다.");
        return cid;
    }
    @Transactional
    public void update(long gid,long cid,ContestRequests.Write request,CustomUserDetails user) {
        GroupService.requireUser(user);
        var old=contest(gid,cid,true);
        groups.requirePermission(gid,user,GroupPermission.MANAGE_CONTESTS);
        boolean upcoming="UPCOMING".equals(status(old,now()));
        validateWrite(request,upcoming,old);
        if(upcoming) {
            var problemIds=lockProblems(gid,request.problemNumbers());
            db.update("""
                UPDATE group_contests SET title=?,starts_at=?,ends_at=?,replay_allowed=?,
                    capture_protection=?,window_switch_protection=? WHERE id=?
                """,request.title().trim(),utc(request.startsAt()),utc(request.endsAt()),
                request.replayAllowed(),request.captureProtection(),request.windowSwitchProtection(),cid);
            db.update("DELETE FROM contest_problems WHERE contest_id=?",cid);
            insertProblems(cid,request.problemNumbers(),problemIds);
            if(!local(old.get("starts_at")).isAfter(now()) || !utc(request.startsAt()).isAfter(now()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"시작된 시험은 문제와 시간을 바꿀 수 없습니다.");
        } else db.update("""
            UPDATE group_contests SET replay_allowed=?,capture_protection=?,window_switch_protection=? WHERE id=?
            """,request.replayAllowed(),request.captureProtection(),request.windowSwitchProtection(),cid);
    }
    private void validateWrite(ContestRequests.Write request,boolean upcoming,Map<String,Object> old) {
        if(request==null || request.title()==null || request.title().isBlank() || request.title().length()>200 ||
            request.startsAt()==null || request.endsAt()==null || request.problemNumbers()==null ||
            request.problemNumbers().isEmpty() || request.problemNumbers().size()>50 ||
            request.problemNumbers().stream().anyMatch(Objects::isNull) ||
            new HashSet<>(request.problemNumbers()).size()!=request.problemNumbers().size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"시험 정보 또는 문제 목록이 올바르지 않습니다.");
        if(!request.startsAt().isBefore(request.endsAt()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"시험 종료 시각은 시작 시각보다 늦어야 합니다.");
        if(old==null || upcoming) {
            if(!utc(request.startsAt()).isAfter(now()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"미래의 시작 시각을 지정해 주세요.");
        } else {
            var existing=db.queryForList("""
                SELECT p.problem_number FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
                WHERE cp.contest_id=? ORDER BY cp.display_order
                """,Integer.class,old.get("id"));
            if(!request.title().trim().equals(old.get("title")) ||
                !utc(request.startsAt()).equals(local(old.get("starts_at"))) ||
                !utc(request.endsAt()).equals(local(old.get("ends_at"))) ||
                !request.problemNumbers().equals(existing))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"시작된 시험은 문제와 시간을 바꿀 수 없습니다.");
        }
    }
    private Map<Integer,Long> lockProblems(long gid,List<Integer> numbers) {
        Map<Integer,Long> ids=new HashMap<>();
        for(int number:numbers) {
            var rows=db.queryForList("SELECT id FROM problems WHERE problem_number=?",number);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"선택한 문제를 찾을 수 없습니다.");
            ids.put(number,id(rows.getFirst().get("id")));
        }
        // 모든 출제자 편집은 문제 행을 먼저 잠급니다. 여러 문제를 한 시험에 묶을 때도
        // ID 오름차순으로 잠가 동시 시험 편집의 잠금 순서를 일치시킵니다.
        for(long problemId:ids.values().stream().sorted().toList()) {
            var rows=db.queryForList("""
                SELECT p.id FROM problems p
                WHERE p.id=? AND p.group_id=? AND p.visibility='GROUP' AND p.status='PUBLISHED'
                AND EXISTS (SELECT 1 FROM problem_test_cases t WHERE t.problem_id=p.id AND t.is_active=TRUE)
                AND EXISTS (SELECT 1 FROM reference_solutions r JOIN problem_languages l
                    ON l.problem_id=r.problem_id AND l.language=r.language
                    WHERE r.problem_id=p.id AND CHAR_LENGTH(TRIM(r.source_code))>0)
                FOR UPDATE
                """,problemId,gid);
            if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"선택한 문제는 시험에 사용할 수 없습니다.");
        }
        return ids;
    }
    private void insertProblems(long cid,List<Integer> numbers,Map<Integer,Long> ids) {
        int order=1;
        for(int number:numbers) db.update("""
            INSERT INTO contest_problems(contest_id,problem_id,display_order) VALUES(?,?,?)
            """,cid,ids.get(number),order++);
    }

    /** 제출 트랜잭션 내부에서 호출합니다. 대상 시험 행을 잠가 시간/문제 수정과 교차하지 않게 합니다. */
    @Transactional
    public void requireSubmission(long contestId,long problemId,CustomUserDetails user) {
        GroupService.requireUser(user);
        var rows=db.queryForList("SELECT * FROM group_contests WHERE id=? FOR UPDATE",contestId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"시험을 찾을 수 없습니다.");
        var c=rows.getFirst();
        requireActiveTake(c,user);
        if(db.queryForObject("""
            SELECT COUNT(*) FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
            JOIN group_contests c ON c.id=cp.contest_id
            WHERE cp.contest_id=? AND cp.problem_id=? AND p.group_id=c.group_id
              AND p.visibility='GROUP'
            """,Long.class,contestId,problemId)==0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"시험에 포함되지 않은 문제입니다.");
    }

    /** 일반 문제 API는 시험 연결 문제를 숨기고 시험 전용 엔드포인트만 응시자에게 엽니다. */
    public void requireQuestionRead(long problemId,CustomUserDetails user) {
        var rows=db.queryForList("""
            SELECT c.group_id,p.author_id FROM contest_problems cp
            JOIN group_contests c ON c.id=cp.contest_id
            JOIN problems p ON p.id=cp.problem_id WHERE cp.problem_id=? LIMIT 1
            """,problemId);
        if(rows.isEmpty()) return;
        if(user!=null && user.isEnabled() && id(rows.getFirst().get("author_id"))==user.getUserId()) return;
        if(questionStaff(id(rows.getFirst().get("group_id")),user)) return;
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    /** 본인 제출은 응시 중 또는 참여했던 시험의 허용된 replay에서만, 스태프는 별도 권한으로 읽습니다. */
    public void requireSubmissionRead(long contestId,long ownerUserId,CustomUserDetails user) {
        long uid=GroupService.requireUser(user);
        var rows=db.queryForList("SELECT * FROM group_contests WHERE id=?",contestId);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var c=rows.getFirst(); long gid=id(c.get("group_id"));
        if(groups.can(gid,user,GroupPermission.VIEW_CONTEST_RESULTS)) return;
        if(uid!=ownerUserId || !personalReadAllowed(c,user,now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"시험 제출을 볼 수 없습니다.");
    }

    public List<Map<String,Object>> problems(long gid,long cid,CustomUserDetails user) {
        groups.requireMember(gid,user);
        var c=contest(gid,cid,false);
        var visible=visibleProblemSummaries(c,user);
        if(visible.isEmpty() && !canReadQuestions(c,user,now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"현재 시험 문제를 볼 수 없습니다.");
        return visible;
    }
    public Map<String,Object> problem(long gid,long cid,int number,CustomUserDetails user) {
        groups.requireMember(gid,user);
        var c=contest(gid,cid,false);
        var rows=db.queryForList("""
            SELECT p.id,p.problem_number,p.problem_title,p.description,p.input_description,
                   p.output_description,p.constraints_text,p.rank_type,p.rank_int,p.time_limit_ms,
                   p.memory_limit_mb,p.execution_mode,p.status,p.author_id,
                   p.function_abi_compatible,p.source_name,p.source_url
            FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
            WHERE cp.contest_id=? AND p.problem_number=? AND p.group_id=? AND p.visibility='GROUP'
            """,cid,number,gid);
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var p=rows.getFirst(); requireQuestionAccess(c,user,id(p.get("id")));
        Map<String,Object> result=new LinkedHashMap<>();
        for(var key:List.of("problem_number","problem_title","description","input_description",
            "output_description","constraints_text","rank_type","rank_int","time_limit_ms",
            "memory_limit_mb","execution_mode","status")) result.put(key,p.get(key));
        result.put("examples",db.queryForList("""
            SELECT input_text,output_text,explanation FROM problem_examples
            WHERE problem_id=? ORDER BY display_order
            """,p.get("id")));
        var languages=db.queryForList("SELECT language FROM problem_languages WHERE problem_id=? ORDER BY language",
            String.class,p.get("id"));
        if("FUNCTION".equals(p.get("execution_mode")) && !enabled(p.get("function_abi_compatible")))
            languages=languages.stream().filter("PYTHON"::equals).toList();
        result.put("languages",languages);
        result.put("tags",db.queryForList("""
            SELECT t.name FROM problem_tags pt JOIN tags t ON t.id=pt.tag_id
            WHERE pt.problem_id=? ORDER BY t.name
            """,String.class,p.get("id")));
        result.put("sourceName",p.get("source_name")); result.put("sourceUrl",p.get("source_url"));
        return result;
    }

    public Map<String,Object> mine(long gid,long cid,CustomUserDetails user) {
        long uid=GroupService.requireUser(user);
        var c=contest(gid,cid,false);
        if(!groups.can(gid,user,GroupPermission.TAKE_CONTESTS) || !personalReadAllowed(c,user,now()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"본인 시험 제출을 볼 수 없습니다.");
        return Map.of("submissions",submissionRows(cid,uid));
    }
    private List<Map<String,Object>> submissionRows(long cid,long uid) {
        var rows=db.queryForList("""
            SELECT s.id,p.problem_number,s.language,s.status,s.score,s.execution_time_ms,
                   s.submitted_at,s.judged_at,s.source_code
            FROM submissions s JOIN problems p ON p.id=s.problem_id
            WHERE s.contest_id=? AND s.user_id=? ORDER BY s.id DESC LIMIT 200
            """,cid,uid);
        return rows.stream().map(r -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",r.get("id")); item.put("problemNumber",r.get("problem_number"));
            item.put("language",r.get("language")); item.put("status",r.get("status"));
            item.put("score",r.get("score")); item.put("executionTimeMs",r.get("execution_time_ms"));
            // 시험 제출은 UTC로 저장합니다. JSON에도 명시적인 Z 시각으로 내보냅니다.
            item.put("submittedAt",iso(r.get("submitted_at")));
            item.put("judgedAt",r.get("judged_at")==null?null:iso(r.get("judged_at")));
            item.put("sourceCode",r.get("source_code")); return item;
        }).toList();
    }
    public List<Map<String,Object>> results(long gid,long cid,CustomUserDetails user) {
        contest(gid,cid,false);
        groups.requirePermission(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
        var rows=db.queryForList("""
            SELECT u.id AS user_id,u.username,COUNT(first_ac.problem_id) AS solved_count,
                COALESCE(SUM(GREATEST(0,TIMESTAMPDIFF(SECOND,c.starts_at,first_ac.first_ac_at))),0)
                    AS time_sum_seconds
            FROM (SELECT DISTINCT user_id FROM submissions WHERE contest_id=?) participants
            JOIN users u ON u.id=participants.user_id
            JOIN group_contests c ON c.id=?
            LEFT JOIN (SELECT user_id,problem_id,MIN(submitted_at) AS first_ac_at
                FROM submissions WHERE contest_id=? AND status='ACCEPTED'
                GROUP BY user_id,problem_id) first_ac ON first_ac.user_id=u.id
            GROUP BY u.id,u.username,c.starts_at
            ORDER BY solved_count DESC,time_sum_seconds ASC,u.id ASC
            """,cid,cid,cid);
        return rows.stream().map(r -> Map.<String,Object>of(
            "userId",r.get("user_id"),"username",r.get("username"),
            "solvedCount",r.get("solved_count"),"timeSumSeconds",r.get("time_sum_seconds"))).toList();
    }
    public Map<String,Object> submissions(long gid,long cid,long targetUid,CustomUserDetails user) {
        contest(gid,cid,false);
        groups.requirePermission(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
        return Map.of("submissions",submissionRows(cid,targetUid));
    }

    @Transactional
    public void event(long gid,long cid,ContestRequests.Event event,CustomUserDetails user) {
        long uid=GroupService.requireUser(user);
        // 제출과 동일한 시험→사용자 순서로 잠가 이벤트 FK의 부모 잠금 경합을 막습니다.
        var c=contest(gid,cid,true);
        if(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid).isEmpty())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        requireActiveTake(c,user);
        if(event==null || event.type()==null || event.eventId()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이벤트 값이 올바르지 않습니다.");
        if(event.type().windowSwitch()?!enabled(c.get("window_switch_protection")):
            !enabled(c.get("capture_protection")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"활성화되지 않은 감시 이벤트입니다.");
        if(db.queryForObject("""
            SELECT COUNT(*) FROM contest_events WHERE contest_id=? AND user_id=? AND event_id=?
            """,Long.class,cid,uid,event.eventId().toString())>0) return;
        long recent=db.queryForObject("""
            SELECT COUNT(*) FROM contest_events WHERE contest_id=? AND user_id=?
              AND occurred_at>=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 MINUTE)
            """,Long.class,cid,uid);
        if(recent>=60) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"감시 이벤트가 너무 잦습니다.");
        int inserted=db.update("""
            INSERT INTO contest_events(contest_id,user_id,event_id,event_type,occurred_at)
            SELECT id,?,?,?,UTC_TIMESTAMP(6) FROM group_contests
            WHERE id=? AND starts_at<=UTC_TIMESTAMP(6) AND ends_at>UTC_TIMESTAMP(6)
            """,uid,event.eventId().toString(),event.type().name(),cid);
        if(inserted!=1) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"시험 응시 시간이 아닙니다.");
    }
    public List<Map<String,Object>> events(long gid,long cid,Long targetUid,CustomUserDetails user) {
        contest(gid,cid,false);
        groups.requirePermission(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
        var rows=targetUid==null ? db.queryForList("""
            SELECT e.id,e.user_id,u.username,e.event_type,e.occurred_at
            FROM contest_events e JOIN users u ON u.id=e.user_id
            WHERE e.contest_id=? ORDER BY e.id DESC LIMIT 500
            """,cid) : db.queryForList("""
            SELECT e.id,e.user_id,u.username,e.event_type,e.occurred_at
            FROM contest_events e JOIN users u ON u.id=e.user_id
            WHERE e.contest_id=? AND e.user_id=? ORDER BY e.id DESC LIMIT 500
            """,cid,targetUid);
        return rows.stream().map(r -> Map.<String,Object>of(
            "id",r.get("id"),"userId",r.get("user_id"),"username",r.get("username"),
            "type",r.get("event_type"),"occurredAt",iso(r.get("occurred_at")))).toList();
    }
}
