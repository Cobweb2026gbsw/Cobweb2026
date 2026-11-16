package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 수십만 개 fixture를 만들지 않고 새 모드의 long OFFSET 및 읽기 스냅샷 계약을 검증합니다. */
class ProblemPaginationTest {
    @Test void metadataCanReachBeyondTheLegacyPageCapAndUsesLongOffsets() {
        var db=mock(JdbcTemplate.class);
        var service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),mock(ContestService.class));
        when(db.queryForObject(anyString(),eq(Long.class),(Object)isNull(),eq("%%"))).thenReturn(300060L);
        when(db.queryForList(anyString(),(Object)isNull(),eq("%%"),eq(300030L))).thenReturn(List.of());
        var page=service.listPage(10001,"",null,"all",null);
        assertEquals(10001,page.page());assertEquals(10002,page.totalPages());assertEquals(300060,page.totalItems());
        verify(db).queryForList(anyString(),(Object)isNull(),eq("%%"),eq(300030L));
    }
    @Test void maximumRequestDoesNotOverflowTheOffsetMultiplication() {
        var db=mock(JdbcTemplate.class);
        var service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),mock(ContestService.class));
        when(db.queryForObject(anyString(),eq(Long.class),(Object)isNull(),eq("%%"))).thenReturn(2147483647L);
        var page=service.listPage(Integer.MAX_VALUE,"",null,"all",null);
        assertEquals(71582788,page.page());assertEquals(71582789,page.totalPages());
        verify(db).queryForList(anyString(),(Object)isNull(),eq("%%"),eq(2147483640L));
    }
    @Test void bothMetadataQueriesUseTheSameReadOnlyRepeatableSnapshot() throws Exception {
        for(String method:List.of("listPage","minePage")) {
            var annotation=ProblemService.class.getMethod(method,int.class,String.class,List.class,String.class,
                com.drewdrew0414.global.security.CustomUserDetails.class).getAnnotation(Transactional.class);
            assertNotNull(annotation);assertTrue(annotation.readOnly());assertEquals(Isolation.REPEATABLE_READ,annotation.isolation());
        }
    }
}
