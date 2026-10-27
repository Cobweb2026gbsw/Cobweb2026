package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.UserStatus;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 실제 DB를 변경하지 않고 계정별 해결 표시·정확한 난이도 쌍·페이지 전 필터를 검증합니다. */
class ProblemListStatusTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ProblemService service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),mock(ContestService.class));

    @Test void publicListUsesOneAccountBoundJoinAndReturnsBooleanInsteadOfAggregateCounts() {
        var original=List.of(row(1000,1),row(1001,0),row(1002,Boolean.TRUE));
        when(db.queryForList(anyString(),eq(5L),eq("%레이저%"),eq(60))).thenReturn(original);

        var result=service.list(2,"레이저",user(5));

        assertEquals(List.of(true,false,true),result.stream().map(row->row.get("solved")).toList());
        assertEquals(1,original.getFirst().get("solved"),"DB 반환 맵 자체를 변경하지 않습니다.");
        var sql=ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(),eq(5L),eq("%레이저%"),eq(60));
        assertTrue(sql.getValue().contains("LEFT JOIN user_solved_problems s ON s.user_id=? AND s.problem_id=p.id"));
        assertTrue(sql.getValue().contains("p.status='PUBLISHED' AND p.visibility='PUBLIC' AND p.group_id IS NULL"));
        assertTrue(sql.getValue().contains("ORDER BY p.problem_number LIMIT 30 OFFSET ?"));
        assertFalse(sql.getValue().contains("FROM submissions"),"코테 결과나 전체 제출 수로 해결을 추론하지 않습니다.");
        verifyNoMoreInteractions(db);
    }

    @Test void anonymousAndDisabledAccountsAlwaysReceiveFalseAndBindNoOtherUserId() {
        when(db.queryForList(anyString(),(Object)isNull(),eq("%%"),eq(0))).thenReturn(List.of(row(1000,1)));
        assertEquals(false,service.list(0,null).getFirst().get("solved"));
        var disabled=user(5);
        ReflectionTestUtils.setField(disabled.getUser(),"status",UserStatus.STOPPED);
        assertEquals(false,service.list(0,null,disabled).getFirst().get("solved"));
        verify(db,times(2)).queryForList(anyString(),(Object)isNull(),eq("%%"),eq(0));
        verifyNoMoreInteractions(db);
    }

    @Test void switchingAccountsChangesOnlyTheBoundSolveLedgerOwner() {
        when(db.queryForList(anyString(),eq(5L),eq("%%"),eq(0))).thenReturn(List.of(row(1000,1)));
        when(db.queryForList(anyString(),eq(8L),eq("%%"),eq(0))).thenReturn(List.of(row(1000,0)));
        assertEquals(true,service.list(0,"",user(5)).getFirst().get("solved"));
        assertEquals(false,service.list(0,"",user(8)).getFirst().get("solved"));
        verify(db).queryForList(anyString(),eq(5L),eq("%%"),eq(0));
        verify(db).queryForList(anyString(),eq(8L),eq("%%"),eq(0));
        verifyNoMoreInteractions(db);
    }

    @Test void exactDifficultyPairsAndSolvedFilterRunBeforePaginationAndDuplicatesDoNotExpandThePairs() {
        service.list(2,"filtered",List.of("ELITE:1","EXPERT:5","ELITE:1"),"solved",user(5));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(),eq(5L),eq("%filtered%"),eq("ELITE"),eq(1),eq("EXPERT"),eq(5),eq(60));
        String query=sql.getValue();
        assertTrue(query.contains("AND ((p.rank_type=? AND p.rank_int=?) OR (p.rank_type=? AND p.rank_int=?))"));
        assertTrue(query.contains("AND s.problem_id IS NOT NULL ORDER BY"));
        assertTrue(query.indexOf("p.rank_int=?")<query.indexOf("LIMIT 30"));
        verifyNoMoreInteractions(db);
    }

    @Test void mineKeepsItsOwnerGuardAndUsesTheSameSolveAndDifficultyFilters() {
        when(db.queryForList(anyString(),eq(5L),eq(5L),eq("%draft%"),eq("ABSOLUTE"),eq(5),eq(30)))
            .thenReturn(List.of(row(1000,0)));
        assertEquals(false,service.mine(1,"draft",List.of("ABSOLUTE:5"),"unsolved",user(5)).getFirst().get("solved"));
        var sql=ArgumentCaptor.forClass(String.class);
        verify(db).queryForList(sql.capture(),eq(5L),eq(5L),eq("%draft%"),eq("ABSOLUTE"),eq(5),eq(30));
        assertTrue(sql.getValue().contains("WHERE p.author_id=? AND p.problem_title LIKE ?"));
        assertTrue(sql.getValue().contains("AND s.problem_id IS NULL ORDER BY p.updated_at DESC,p.id DESC"));
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.mine(0,"",null)).getStatusCode().value());
        verifyNoMoreInteractions(db);
    }

    @Test void malformedOrOversizedFiltersAreRejectedWithoutDbAccess() {
        for(String invalid:List.of("ELITE:0","ELITE:6","elite:1","ELITE","ELITE:1:2"," ELITE:1","ELITE:1 ","EXPERT:01","ELITE:1' OR 1=1"))
            assertEquals(400,assertThrows(ResponseStatusException.class,
                ()->service.list(0,"",List.of(invalid),"all",user(5))).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,
            ()->service.list(0,"",Collections.nCopies(36,"ELITE:1"),"all",user(5))).getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,
            ()->service.list(0,"",null,"SOLVED",user(5))).getStatusCode().value());
        for(String status:List.of("solved","unsolved"))
            assertEquals(401,assertThrows(ResponseStatusException.class,
                ()->service.list(0,"",null,status,null)).getStatusCode().value());
        verifyNoInteractions(db);
    }

    @Test void controllerPassesOnlyTheAuthenticatedPrincipalAndFilterValuesToBothLists() {
        var mocked=mock(ProblemService.class);
        var controller=new ProblemController(mocked);
        var account=user(5);
        var selected=List.of("SKILLED:2","LEGEND:4");
        controller.list(1,"search",selected,"solved",account);
        controller.mine(2,"mine",selected,"unsolved",account);
        verify(mocked).list(1,"search",selected,"solved",account);
        verify(mocked).mine(2,"mine",selected,"unsolved",account);
        verifyNoMoreInteractions(mocked);
    }

    private static Map<String,Object> row(int number,Object solved) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("problem_number",number);row.put("accepted_count",999);row.put("solved",solved);
        return row;
    }
    private static CustomUserDetails user(long id) {
        var entity=User.builder().username("solver").password("unused").build();
        ReflectionTestUtils.setField(entity,"id",id);
        return new CustomUserDetails(entity);
    }
}
