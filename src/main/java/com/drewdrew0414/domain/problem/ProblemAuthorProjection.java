package com.drewdrew0414.domain.problem;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 일반 풀이와 시험 풀이에서 동일한 제작자 공개 경계를 사용합니다. */
public final class ProblemAuthorProjection {
    private ProblemAuthorProjection() {}

    public static boolean isPublic(Object value) {
        // JDBC의 Boolean/0·1만 인정하며 레거시 누락·알 수 없는 값은 비공개로 처리합니다.
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.doubleValue()==1.0;
    }

    public static void put(Map<String,Object> result,Map<String,Object> problem,JdbcTemplate db) {
        boolean visible=isPublic(problem.get("author_public"));
        result.put("authorPublic",visible);
        // 비공개면 이름을 조회하거나 응답 객체에 복사하지 않습니다. id·email도 반환하지 않습니다.
        result.put("authorUsername",visible
            ? db.queryForObject("SELECT username FROM users WHERE id=?",String.class,problem.get("author_id"))
            : null);
    }
}
