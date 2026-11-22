package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ContestCopyService {
    private final ContestService contests;
    private final GroupService groups;
    private final JdbcTemplate db;

    @Transactional
    public long copy(long gid, long cid, long target, Instant start, Instant end, UUID key, CustomUserDetails user) {
        start=start.truncatedTo(ChronoUnit.MICROS);
        end=end.truncatedTo(ChronoUnit.MICROS);
        long uid = GroupService.requireUser(user);
        groups.requirePermission(gid,user,GroupPermission.MANAGE_CONTESTS);
        // 기존 제출·감시 이벤트와 동일한 시험 → 사용자 → 문제 잠금 순서입니다.
        var source = contests.contest(gid,cid,true);
        if (db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",Long.class,uid).isEmpty())
            throw new ApiException(HttpStatus.UNAUTHORIZED);
        var locked = db.queryForList("""
                SELECT id FROM study_groups WHERE id IN (?,?) AND status='ACCEPTED' ORDER BY id FOR UPDATE
                """,Long.class,gid,target);
        if (!locked.contains(gid) || !locked.contains(target)) throw new ApiException(HttpStatus.NOT_FOUND,"그룹을 찾을 수 없습니다.");
        groups.requirePermission(gid,user,GroupPermission.MANAGE_CONTESTS);
        groups.requirePermission(target,user,GroupPermission.CREATE_CONTESTS);
        if (target != gid) {
            groups.requirePermission(gid,user,GroupPermission.EDIT_PROBLEMS);
            groups.requirePermission(target,user,GroupPermission.MANAGE_CONTESTS);
            groups.requirePermission(target,user,GroupPermission.CREATE_PROBLEMS);
            groups.requirePermission(target,user,GroupPermission.REVIEW_PROBLEMS);
        }
        var previous = db.queryForList("""
                SELECT id,copied_from_contest_id,group_id,starts_at,ends_at FROM group_contests
                WHERE created_by=? AND copy_request_id=?
                """,uid,key);
        if (!previous.isEmpty()) {
            var copy=previous.getFirst();
            if (copy.get("group_id").equals(target) && copy.get("copied_from_contest_id").equals(cid)
                    && Instant.parse(ContestService.iso(copy.get("starts_at"))).equals(start)
                    && Instant.parse(ContestService.iso(copy.get("ends_at"))).equals(end))
                return ((Number)copy.get("id")).longValue();
            throw new ApiException(HttpStatus.CONFLICT,"이미 처리된 복제 요청입니다.");
        }
        // 아직 시작하지 않은 원본 시험에서 문제를 빼고 편집한 뒤 다시 출제할 수도 있습니다.
        // 비교부터 새 시험 연결까지 원본과 현재 대상 복제본의 내용 및 상태를 고정합니다.
        db.queryForList("""
                WITH source AS (SELECT problem_id FROM contest_problems WHERE contest_id=?)
                SELECT id FROM problems WHERE id IN (SELECT problem_id FROM source)
                    OR (group_id=? AND copied_from_problem_id IN (SELECT problem_id FROM source))
                ORDER BY id FOR UPDATE
                """,Long.class,cid,target);
        var sourceProblems = db.queryForList("""
                SELECT p.id,p.problem_number FROM contest_problems cp JOIN problems p ON p.id=cp.problem_id
                WHERE cp.contest_id=? AND p.group_id=? AND p.status='PUBLISHED' AND p.visibility='GROUP' ORDER BY cp.display_order
                """,cid,gid);
        if (sourceProblems.size()!=db.queryForObject("SELECT count(*) FROM contest_problems WHERE contest_id=?",Integer.class,cid))
            throw new ApiException(HttpStatus.CONFLICT,"원본 시험에 사용할 수 없는 문제가 있습니다.");
        List<Integer> numbers = new ArrayList<>();
        for (var row:sourceProblems) {
            int number=((Number)row.get("problem_number")).intValue();
            numbers.add(target == gid ? number : copyProblem(((Number)row.get("id")).longValue(),target,uid));
        }
        var request=new ContestRequests.Write((String)source.get("title"),start,end,numbers,(Boolean)source.get("replay_allowed"),
                (Boolean)source.get("capture_protection"),(Boolean)source.get("window_switch_protection"),(Boolean)source.get("autocomplete_allowed"));
        long newContest=contests.create(target,request,user);
        db.update("UPDATE group_contests SET copied_from_contest_id=?,copy_request_id=? WHERE id=?",cid,key,newContest);
        return newContest;
    }

    private int copyProblem(long source,long target,long uid) {
        var existing=db.queryForList("SELECT id,problem_number FROM problems WHERE group_id=? AND copied_from_problem_id=?",
                target,source);
        if (!existing.isEmpty()) {
            var copy=existing.getFirst();
            requireSameContent(source,((Number)copy.get("id")).longValue());
            return ((Number)copy.get("problem_number")).intValue();
        }
        // 시험에 이미 연결된 문제는 원본 편집이 잠겨 있습니다. 대상 그룹의 제목 충돌은 전체 복제를 되돌립니다.
        int number=db.queryForObject("""
                UPDATE problem_number_sequence SET next_number=greatest(next_number,
                  (SELECT coalesce(max(problem_number),999)+1 FROM problems))+1 WHERE id=1 RETURNING next_number-1
                """,Integer.class);
        long id;
        try {
            id=db.queryForObject("""
                INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,constraints_text,
                    rank_type,rank_int,time_limit_ms,memory_limit_mb,judge_type,execution_mode,function_abi_compatible,function_signature,
                    language_adjusted_time,status,visibility,requested_visibility,author_id,author_public,group_id,source_name,source_url,published_at,copied_from_problem_id)
                SELECT ?,problem_title,description,input_description,output_description,constraints_text,rank_type,rank_int,
                    time_limit_ms,memory_limit_mb,judge_type,execution_mode,function_abi_compatible,function_signature,language_adjusted_time,
                    'PUBLISHED','GROUP','GROUP',?,FALSE,?,source_name,source_url,(clock_timestamp() AT TIME ZONE 'UTC'),id
                FROM problems WHERE id=? RETURNING id
                """,Long.class,number,uid,target,source);
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(HttpStatus.CONFLICT,"대상 그룹에 같은 제목의 다른 문제가 있습니다.");
        }
        // 사용자 제출, 좋아요, 풀이 기록은 복사하지 않습니다. 실제 채점을 재현하는 자료만 복사합니다.
        db.update("INSERT INTO problem_examples(problem_id,input_text,output_text,explanation,display_order) SELECT ?,input_text,output_text,explanation,display_order FROM problem_examples WHERE problem_id=?",id,source);
        db.update("INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,score,display_order,is_active) SELECT ?,input_data,expected_output,compare_type,score,display_order,is_active FROM problem_test_cases WHERE problem_id=?",id,source);
        db.update("INSERT INTO problem_tags(problem_id,tag_id) SELECT ?,tag_id FROM problem_tags WHERE problem_id=?",id,source);
        db.update("INSERT INTO problem_languages(problem_id,language) SELECT ?,language FROM problem_languages WHERE problem_id=?",id,source);
        db.update("INSERT INTO reference_solutions(problem_id,language,file_name,source_code) SELECT ?,language,file_name,source_code FROM reference_solutions WHERE problem_id=?",id,source);
        db.update("INSERT INTO problem_starter_codes(problem_id,language,source_code) SELECT ?,language,source_code FROM problem_starter_codes WHERE problem_id=?",id,source);
        db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,'PUBLISHED','코딩테스트 그룹 복제',?)",id,uid);
        return number;
    }

    // 비교 열은 출제 시 복사하는 자료와 동일합니다. 큰 테스트 입력은 서버로 전송하지 않고 DB에서 비교합니다.
    private void requireSameContent(long source,long copy) {
        sameRows("problems","id","problem_title,description,input_description,output_description,constraints_text,"
                +"rank_type,rank_int,time_limit_ms,memory_limit_mb,judge_type,execution_mode,function_abi_compatible,"
                +"function_signature,language_adjusted_time,source_name,source_url",source,copy);
        sameRows("problem_examples","problem_id","input_text,output_text,explanation,display_order",source,copy);
        sameRows("problem_test_cases","problem_id","input_data,expected_output,compare_type,score,display_order,is_active",source,copy);
        sameRows("problem_tags","problem_id","tag_id",source,copy);
        sameRows("problem_languages","problem_id","language",source,copy);
        sameRows("reference_solutions","problem_id","language,file_name,source_code",source,copy);
        sameRows("problem_starter_codes","problem_id","language,source_code",source,copy);
    }

    private void sameRows(String table,String key,String fields,long source,long copy) {
        // table/key/fields는 위의 고정 문자열만 사용합니다. 자식 행의 고유 키를 열에 포함하여 누락/추가도 구별합니다.
        boolean differs=db.queryForObject("SELECT EXISTS (SELECT 1 FROM "+table+" WHERE "+key+
                " IN (?,?) GROUP BY "+fields+" HAVING count(DISTINCT "+key+")<>2)",Boolean.class,source,copy);
        if (differs) throw new ApiException(HttpStatus.CONFLICT,"기존 그룹 복제본과 원본 문제 내용이 다릅니다. 새 문제로 검수 후 출제해 주세요.");
    }
}
