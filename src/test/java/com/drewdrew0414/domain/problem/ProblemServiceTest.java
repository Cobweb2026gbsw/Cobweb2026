package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupPermission;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 저장 전 권한·제목 범위·민감 필드·참고정답 검증의 빠른 회귀 테스트입니다. */
class ProblemServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final GroupService groups=mock(GroupService.class);
    private final ContestService contests=mock(ContestService.class);
    private final ProblemService service=new ProblemService(db,new ObjectMapper(),groups,contests);

    @Test void titleIsNormalizedAndCheckedWithinItsScopeBeforeAllocatingANumber() {
        var user=user(5,"USER");
        allowUserLock();
        var request=write("  Same   title  ",null,null,List.of(),null);
        when(db.queryForObject(contains("title_scope=?"),eq(Long.class),eq(0L),eq("Same title")))
            .thenReturn(1L);

        var error=assertThrows(ResponseStatusException.class,()->service.create(request,user));

        assertEquals(409,error.getStatusCode().value());
        verify(db,never()).queryForObject(contains("next_number"),eq(Integer.class));
    }

    @Test void sameTitleCanBeCheckedSeparatelyForAGroupAndRequiresCreatePermission() {
        var user=user(5,"USER");
        allowUserLock();
        var request=write(" Same title ",42L,"GROUP",List.of(),null);
        when(db.queryForObject(contains("title_scope=?"),eq(Long.class),eq(42L),eq("Same title")))
            .thenReturn(1L);

        var error=assertThrows(ResponseStatusException.class,()->service.create(request,user));

        assertEquals(409,error.getStatusCode().value());
        verify(groups).requirePermission(42L,user,GroupPermission.CREATE_PROBLEMS);
        verify(db,never()).queryForObject(contains("next_number"),eq(Integer.class));
    }

    @Test void pathLikeReferenceNamesAndOversizedUtf8SourcesAreRejectedBeforeStorage() {
        var user=user(5,"USER");
        var path=write("Path",null,null,List.of("PYTHON"),
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","../answer.py","print(1)")));
        var oversize=write("Large",null,null,List.of("PYTHON"),
            List.of(new ProblemRequests.ReferenceSolution("PYTHON","answer.py","한".repeat(22000))));

        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.create(path,user))
            .getStatusCode().value());
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.create(oversize,user))
            .getStatusCode().value());
        verifyNoInteractions(db);
    }

    @Test void privateEditorDataIsDeniedToANonOwnerEvenWhenPublicDetailCanBeRead() {
        var user=user(8,"USER");
        var p=problem(5,"PUBLISHED","PUBLIC",null);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));

        var error=assertThrows(ResponseStatusException.class,()->service.edit(1001,user));

        assertEquals(403,error.getStatusCode().value());
        verify(db,never()).queryForList(contains("reference_solutions"),any(Object[].class));
    }

    @Test void groupAuthorLosesPrivateEditorAccessWhenCreatePermissionIsRevoked() {
        var user=user(5,"USER");
        var p=problem(5,"DRAFT","GROUP",42L);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));

        var error=assertThrows(ResponseStatusException.class,()->service.edit(1001,user));

        assertEquals(403,error.getStatusCode().value());
        verify(groups).can(42L,user,GroupPermission.CREATE_PROBLEMS);
        verify(db,never()).queryForList(contains("reference_solutions"),any(Object[].class));
    }

    @Test void reviewRequestNeedsAnAllowedLanguageReference() {
        var user=user(5,"USER");
        allowUserLock();
        var p=problem(5,"DRAFT","PRIVATE",null);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        when(db.queryForObject(contains("problem_test_cases"),eq(Long.class),eq(77L))).thenReturn(1L);
        when(db.queryForObject(contains("reference_solutions r"),eq(Long.class),eq(77L),eq(false)))
            .thenReturn(0L);

        var error=assertThrows(ResponseStatusException.class,()->service.requestReview(1001,user));

        assertEquals(409,error.getStatusCode().value());
        verify(db,never()).update(contains("problem_status_history"),any(),any(),any(),any());
    }

    @Test void contestLinkedProblemCannotHaveItsTestsOrReferenceReplaced() {
        var user=user(5,"USER");
        allowUserLock();
        var p=problem(5,"DRAFT","PRIVATE",null);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(p));
        when(db.queryForObject(contains("status IN ('QUEUED','RUNNING')"),eq(Long.class),eq(77L)))
            .thenReturn(0L);
        when(db.queryForObject(contains("contest_problems"),eq(Long.class),eq(77L))).thenReturn(1L);

        var error=assertThrows(ResponseStatusException.class,()->service.update(1001,
            write("New",null,null,List.of(),null),user));

        assertEquals(409,error.getStatusCode().value());
        verify(db,never()).update(contains("DELETE FROM problem_test_cases"),any(Object[].class));
    }

    @Test void adminListingRequiresOperatorOnTheServer() {
        var error=assertThrows(ResponseStatusException.class,()->service.adminList(0,"","","",user(5,"USER")));
        assertEquals(403,error.getStatusCode().value());
        verifyNoInteractions(db);
    }

    private static ProblemRequests.Write write(String title,Long groupId,String visibility,
                                               List<String> languages,
                                               List<ProblemRequests.ReferenceSolution> references) {
        return new ProblemRequests.Write(title,"body","input","output",null,"INITIATE",1,1000,128,
            "STDIN",List.of(),List.of(),languages,null,null,null,references,groupId,visibility);
    }
    private void allowUserLock() {
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",5L))
            .thenReturn(List.of(Map.of("id",5L)));
    }
    private static Map<String,Object> problem(long author,String status,String visibility,Long groupId) {
        var p=new HashMap<String,Object>();
        p.put("id",77L); p.put("problem_number",1001); p.put("author_id",author);
        p.put("status",status); p.put("visibility",visibility); p.put("group_id",groupId);
        p.put("execution_mode","STDIN"); p.put("requested_visibility","PUBLIC");
        return p;
    }
    private static CustomUserDetails user(long id,String role) {
        User entity=User.builder().username("tester").password("unused").build();
        ReflectionTestUtils.setField(entity,"id",id);
        ReflectionTestUtils.setField(entity,"role",Role.valueOf(role));
        return new CustomUserDetails(entity);
    }
}
