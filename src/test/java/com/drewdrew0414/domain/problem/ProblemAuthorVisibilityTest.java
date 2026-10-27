package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 제작자 공개는 문제 visibility와 별개이며 구형 요청은 동의를 새로 만들지 않습니다. */
class ProblemAuthorVisibilityTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ProblemService service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),mock(ContestService.class));

    @Test void onlyExplicitBooleanOrNumericOneMeansPublic() {
        assertTrue(ProblemAuthorProjection.isPublic(true));
        assertTrue(ProblemAuthorProjection.isPublic(1));
        assertTrue(ProblemAuthorProjection.isPublic(1L));
        for(var value:Arrays.asList(null,false,0,2,1.5,"true","1"))
            assertFalse(ProblemAuthorProjection.isPublic(value));
    }

    @Test void privateProjectionDoesNotReadOrCopyAuthorIdentity() {
        var p=problem();p.put("author_public",false);p.put("author_username","must-not-leak");
        var result=new LinkedHashMap<String,Object>();
        ProblemAuthorProjection.put(result,p,db);
        assertEquals(false,result.get("authorPublic"));
        assertTrue(result.containsKey("authorUsername"));assertNull(result.get("authorUsername"));
        assertEquals(2,result.size());verifyNoInteractions(db);
    }

    @Test void publicProjectionReadsOnlyTheCurrentAuthorUsernameFromTheOriginalId() {
        var p=problem();p.put("author_public",1);
        when(db.queryForObject("SELECT username FROM users WHERE id=?",String.class,5L)).thenReturn("maker-name");
        var result=new LinkedHashMap<String,Object>();
        ProblemAuthorProjection.put(result,p,db);
        assertEquals(Map.of("authorPublic",true,"authorUsername","maker-name"),result);
        verify(db).queryForObject("SELECT username FROM users WHERE id=?",String.class,5L);
        verifyNoMoreInteractions(db);
    }

    @Test void ordinaryDetailUsesThePrivacyProjectionEvenForTheOwner() {
        var p=problem();p.put("status","PUBLISHED");p.put("visibility","PUBLIC");
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        for(CustomUserDetails viewer:Arrays.asList(null,user(5),user(8))) {
            var result=service.detail(1001,viewer);
            assertEquals(false,result.get("authorPublic"));assertNull(result.get("authorUsername"));
            assertFalse(result.containsKey("author_id"));assertFalse(result.containsKey("authorUsernameId"));
        }
        verify(db,never()).queryForObject(contains("SELECT username"),eq(String.class),any());
        p.put("author_public",true);
        when(db.queryForObject("SELECT username FROM users WHERE id=?",String.class,5L)).thenReturn("maker-name");
        assertEquals("maker-name",service.detail(1001,null).get("authorUsername"));
    }

    @Test void editorReturnsTheStoredFlagWithoutAddingAnAuthorNameField() {
        var p=editable();p.put("author_public",true);
        var result=service.edit(1001,user(5));
        assertEquals(true,result.get("authorPublic"));assertFalse(result.containsKey("authorUsername"));
        p.put("author_public",false);
        assertEquals(false,service.edit(1001,user(5)).get("authorPublic"));
    }

    @Test void creationDefaultsPrivateAndStoresOnlyAnExplicitPublicChoice() {
        allowUser(5);
        when(db.queryForObject(contains("next_number"),eq(Integer.class))).thenReturn(1001);
        when(db.queryForObject(contains("MAX(problem_number)"),eq(Integer.class))).thenReturn(1000);
        when(db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,1001)).thenReturn(77L);
        for(Boolean flag:Arrays.asList(null,false,true)) {
            clearInvocations(db);
            assertEquals(1001,service.create(write(flag),user(5)));
            var args=argumentsOf("INSERT INTO problems(");
            assertEquals(Boolean.TRUE.equals(flag),args.getLast());
            assertEquals(5L,args.get(11)); // 클라이언트가 아닌 로그인한 원저작자 ID
        }
    }

    @Test void updateUsesNullableCoalesceSoOmittedChoiceIsPreserved() {
        editable();
        for(Boolean flag:Arrays.asList(null,false,true)) {
            clearInvocations(db);
            service.update(1001,write(flag),user(5));
            var args=argumentsOf("UPDATE problems SET");
            assertEquals(flag,args.get(args.size()-2));
            assertEquals(77L,args.getLast());
            var sql=mockingDetails(db).getInvocations().stream()
                .map(i->i.getArgument(0)).filter(v->v instanceof String s && s.stripLeading().startsWith("UPDATE problems SET"))
                .findFirst().orElseThrow().toString();
            assertTrue(sql.contains("author_public=COALESCE(?,author_public)"));
        }
    }

    @Test void publicChoiceDoesNotBypassExistingOwnershipChecks() {
        editable();allowUser(8);
        // 타인의 비공개 초안은 존재 자체를 숨기는 기존 404 경계를 유지합니다.
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->service.update(1001,write(true),user(8))).getStatusCode().value());
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i->
            i.getMethod().getName().equals("update") && i.getArgument(0).toString().contains("author_public")));
    }

    @Test void legacyConstructorsLeaveThePrivacyChoiceUnspecified() {
        var minimal=new ProblemRequests.Write("Title","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of());
        var oldFull=new ProblemRequests.Write("Title","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),null,null,null,null,null,null,"PUBLIC");
        var starterFull=new ProblemRequests.Write("Title","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),null,null,null,null,null,null,"PUBLIC",List.of());
        assertNull(minimal.authorPublic());assertNull(oldFull.authorPublic());assertNull(starterFull.authorPublic());
    }

    private List<Object> argumentsOf(String prefix) {
        var invocation=mockingDetails(db).getInvocations().stream()
            .filter(i->i.getMethod().getName().equals("update") && i.getArgument(0).toString().stripLeading().startsWith(prefix))
            .findFirst().orElseThrow();
        return Arrays.asList((Object[])invocation.getRawArguments()[1]);
    }
    private Map<String,Object> editable() {
        var p=problem();allowUser(5);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        when(db.queryForObject(contains("contest_problems"),eq(Long.class),eq(77L))).thenReturn(0L);
        when(db.queryForObject(contains("status IN ('QUEUED','RUNNING')"),eq(Long.class),eq(77L))).thenReturn(0L);
        return p;
    }
    private void allowUser(long id) {
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",id)).thenReturn(List.of(Map.of("id",id)));
    }
    private static Map<String,Object> problem() {
        var p=new HashMap<String,Object>();
        p.put("id",77L);p.put("problem_number",1001);p.put("author_id",5L);p.put("author_public",false);
        p.put("status","DRAFT");p.put("visibility","PRIVATE");p.put("requested_visibility","PUBLIC");
        p.put("execution_mode","STDIN");return p;
    }
    private static ProblemRequests.Write write(Boolean flag) {
        return new ProblemRequests.Write("Author test","body","in","out",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),List.of("PYTHON"),List.of(),null,null,List.of(),null,"PUBLIC",List.of(),flag);
    }
    private static CustomUserDetails user(long id) {
        var entity=User.builder().username("maker-name").password("unused").build();
        ReflectionTestUtils.setField(entity,"id",id);ReflectionTestUtils.setField(entity,"role",Role.USER);
        return new CustomUserDetails(entity);
    }
}
