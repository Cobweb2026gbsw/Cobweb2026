package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

/**
 * 문제 저장과 접근 권한을 담당합니다. 기존 인증은 JPA를 유지하고,
 * 목록·통계·잠금이 필요한 새 기능은 명시적인 SQL로 처리합니다.
 * SELECT * 응답을 피하여 비공개 테스트와 관리용 필드가 사용자에게 노출되지 않게 합니다.
 */
@Service @RequiredArgsConstructor
public class ProblemService {
    private final JdbcTemplate db;
    public static boolean operator(CustomUserDetails user) {
        return user != null && user.getAuthorities().stream()
            .anyMatch(a -> Set.of("ROLE_OPERATOR","ROLE_DEVELOPER").contains(a.getAuthority()));
    }
    public static long requireUser(CustomUserDetails user) {
        if (user == null || !user.isEnabled()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return user.getUserId();
    }
    public static void requireOperator(CustomUserDetails user) {
        requireUser(user);
        if (!operator(user)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public List<Map<String,Object>> list(int page, String search) {
        return db.queryForList("""
            SELECT problem_number, problem_title, rank_type, rank_int, submission_count, accepted_count
            FROM problems WHERE status='PUBLISHED' AND visibility='PUBLIC'
            AND problem_title LIKE ? ORDER BY problem_number LIMIT 30 OFFSET ?
            """, "%" + search + "%", Math.min(Math.max(page,0),10000)*30);
    }
    public Map<String,Object> load(int number, CustomUserDetails user) {
        var rows = db.queryForList("SELECT * FROM problems WHERE problem_number=?", number);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var p = rows.getFirst();
        boolean owner = user != null && Objects.equals(((Number)p.get("author_id")).longValue(), user.getUserId());
        boolean published = "PUBLISHED".equals(p.get("status"));
        boolean member = user != null && p.get("group_id") != null &&
            db.queryForObject("SELECT COUNT(*) FROM group_members WHERE group_id=? AND user_id=?", Long.class,
                p.get("group_id"), user.getUserId()) > 0;
        if (!operator(user) && !owner && !(published && ("PUBLIC".equals(p.get("visibility")) ||
                ("GROUP".equals(p.get("visibility")) && member))))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return p;
    }
    public Map<String,Object> detail(int number, CustomUserDetails user) {
        var p = load(number,user);
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("problem_number","problem_title","description","input_description",
                "output_description","constraints_text","rank_type","rank_int","time_limit_ms",
                "memory_limit_mb","execution_mode","status")) result.put(key,p.get(key));
        result.put("examples", db.queryForList(
            "SELECT input_text, output_text, explanation FROM problem_examples WHERE problem_id=? ORDER BY display_order",p.get("id")));
        return result;
    }
    @Transactional
    public int create(ProblemRequests.Write request, CustomUserDetails user) {
        long uid = requireUser(user);
        // 번호 시퀀스를 잠근 상태에서 이미 존재하는 수동 시드 번호도 건너뜁니다.
        int next = db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1 FOR UPDATE",Integer.class);
        int maximum = db.queryForObject("SELECT COALESCE(MAX(problem_number),999) FROM problems",Integer.class);
        int number = Math.max(next,maximum+1);
        db.update("UPDATE problem_number_sequence SET next_number=? WHERE id=1",number+1);
        db.update("""
            INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
              author_id,status,visibility,execution_mode)
            VALUES(?,?,?,?,?,?,'DRAFT','PRIVATE',?)
            """,number,request.title(),request.description(),request.inputDescription(),request.outputDescription(),uid,request.executionMode());
        update(number,request,user);
        return number;
    }
    @Transactional
    public void update(int number, ProblemRequests.Write r, CustomUserDetails user) {
        requireUser(user);
        // 편집과 제출이 교차해 테스트 데이터가 바뀌지 않도록 문제 행을 잠급니다.
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p = load(number,user);
        if (!operator(user) && ((Number)p.get("author_id")).longValue()!=user.getUserId())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!Set.of("DRAFT","REJECTED").contains(p.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"초안 또는 반려된 문제만 수정할 수 있습니다.");
        db.update("""
            UPDATE problems SET problem_title=?,description=?,input_description=?,output_description=?,
             constraints_text=?,rank_type=?,rank_int=?,time_limit_ms=?,memory_limit_mb=?,execution_mode=?
            WHERE id=?
            """,r.title(),r.description(),r.inputDescription(),r.outputDescription(),r.constraints(),
                r.rankType(),r.rankInt(),r.timeLimitMs(),r.memoryLimitMb(),r.executionMode(),p.get("id"));
        db.update("DELETE FROM problem_examples WHERE problem_id=?",p.get("id"));
        db.update("DELETE FROM problem_test_cases WHERE problem_id=?",p.get("id"));
        int order=0;
        for(var e:r.examples()) db.update(
            "INSERT INTO problem_examples(problem_id,input_text,output_text,explanation,display_order) VALUES(?,?,?,?,?)",
            p.get("id"),e.input(),e.output(),e.explanation(),++order);
        order=0;
        for(var t:r.tests()) db.update(
            "INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,display_order) VALUES(?,?,?,'EXACT',?)",
            p.get("id"),t.input(),t.expected(),++order);
        db.update("INSERT IGNORE INTO problem_languages(problem_id,language) VALUES(?,'PYTHON')",p.get("id"));
    }
    @Transactional
    public void requestReview(int number,CustomUserDetails user) {
        requireUser(user);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=load(number,user);
        if (((Number)p.get("author_id")).longValue()!=user.getUserId() && !operator(user))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!Set.of("DRAFT","REJECTED").contains(p.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        change(p,"PENDING_REVIEW","검수 요청",user.getUserId());
    }
    @Transactional
    public void review(int number,ProblemRequests.Review r,CustomUserDetails user) {
        requireOperator(user);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=load(number,user);
        if (!"ARCHIVED".equals(r.status()) && !"PENDING_REVIEW".equals(p.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        if ("PUBLISHED".equals(r.status()) && db.queryForObject(
            "SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE",Long.class,p.get("id"))==0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"활성 테스트 케이스가 필요합니다.");
        change(p,r.status(),r.cause(),user.getUserId());
    }
    private void change(Map<String,Object> p,String state,String cause,long uid) {
        db.update("UPDATE problems SET status=?,visibility=IF(?='PUBLISHED','PUBLIC',visibility),published_at=IF(?='PUBLISHED',CURRENT_TIMESTAMP,published_at) WHERE id=?",
            state,state,state,p.get("id"));
        db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,?,?,?)",p.get("id"),state,cause,uid);
    }
}
