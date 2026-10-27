package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContestAuthorVisibilityTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ContestService contests=new ContestService(db,mock(GroupService.class));

    @Test void contestQuestionUsesTheSamePrivateProjectionEvenForItsAuthor() {
        prepare(false);
        var result=contests.problem(9,5,1001,user());
        assertEquals(false,result.get("authorPublic"));
        assertTrue(result.containsKey("authorUsername"));assertNull(result.get("authorUsername"));
        assertFalse(result.containsKey("author_id"));assertFalse(result.containsKey("author_public"));
        verify(db,never()).queryForObject(contains("SELECT username"),eq(String.class),any());
    }

    @Test void contestQuestionIncludesTheNameOnlyWithAnExplicitPublicFlag() {
        prepare(true);
        when(db.queryForObject("SELECT username FROM users WHERE id=?",String.class,42L)).thenReturn("contest-maker");
        var result=contests.problem(9,5,1001,user());
        assertEquals(true,result.get("authorPublic"));assertEquals("contest-maker",result.get("authorUsername"));
        assertFalse(result.containsKey("author_id"));
    }
    @Test void genericFunctionContractAndAllAllowedLanguagesReachContestReadersWithoutPrivateTests() {
        prepare(false);
        var signature=new com.drewdrew0414.domain.problem.FunctionSignature("solution",List.of(
            new com.drewdrew0414.domain.problem.FunctionSignature.Parameter("value","int")),"int");
        var p=new HashMap<String,Object>();p.put("id",77L);p.put("author_id",42L);p.put("execution_mode","FUNCTION");
        p.put("function_signature",signature.toJson(new tools.jackson.databind.ObjectMapper()));
        when(db.queryForList(contains("FROM contest_problems cp JOIN problems p"),eq(5L),eq(1001),eq(9L))).thenReturn(List.of(p));
        when(db.queryForList("SELECT language FROM problem_languages WHERE problem_id=? ORDER BY language",String.class,77L))
            .thenReturn(com.drewdrew0414.domain.submission.Language.forMode("FUNCTION"));
        var result=contests.problem(9,5,1001,user());
        assertEquals("GENERIC_V1",result.get("functionAbi"));assertEquals(signature,result.get("functionSignature"));
        assertEquals(15,((List<?>)result.get("languages")).size());
        assertFalse(result.containsKey("tests"));assertFalse(result.containsKey("referenceSolutions"));
    }

    private void prepare(boolean visible) {
        when(db.queryForList("SELECT * FROM group_contests WHERE group_id=? AND id=?",9L,5L))
            .thenReturn(List.of(Map.of("id",5L,"group_id",9L)));
        var p=new HashMap<String,Object>();
        p.put("id",77L);p.put("problem_number",1001);p.put("author_id",42L);p.put("author_public",visible);
        p.put("execution_mode","STDIN");
        when(db.queryForList(contains("FROM contest_problems cp JOIN problems p"),eq(5L),eq(1001),eq(9L)))
            .thenAnswer(invocation->{
                assertTrue(invocation.getArgument(0).toString().contains("p.author_public"));
                return List.of(p);
            });
        // 문제 원저작자는 기존 시험 열람 규칙에 따라 시간창과 무관하게 열람할 수 있습니다.
        when(db.queryForList("SELECT author_id FROM problems WHERE id=?",77L))
            .thenReturn(List.of(Map.of("author_id",42L)));
    }
    private static CustomUserDetails user() {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);when(user.getUserId()).thenReturn(42L);
        return user;
    }
}
