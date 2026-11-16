package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.user.entity.*;
import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 화면의 버튼 유무와 무관하게 서비스가 지키는 숨긴 문제 접근 조건의 행렬입니다. */
class ProblemAccessConditionsTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final GroupService groups=mock(GroupService.class);
    private final ContestService contests=mock(ContestService.class);
    private final ProblemService problems=new ProblemService(db,new ObjectMapper(),groups,contests);

    @Test void bothActivePlatformAdministratorRolesMayReadHiddenProblems() {
        prepare("ARCHIVED","PRIVATE",null);
        for(var role:List.of(Role.OPERATOR,Role.DEVELOPER)) assertDoesNotThrow(()->problems.load(1001,user(8,role,UserStatus.ACTIVE)));
        verifyNoInteractions(groups);
    }

    @Test void originalAuthorRetainsOwnDraftButOtherUsersAndAnonymousCannotDiscoverIt() {
        prepare("DRAFT","PRIVATE",null);
        assertDoesNotThrow(()->problems.load(1001,user(5,Role.USER,UserStatus.ACTIVE)));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->problems.load(1001,user(8,Role.USER,UserStatus.ACTIVE))).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->problems.load(1001,null)).getStatusCode().value());
    }

    @Test void disabledOrBannedPlatformRolesDoNotKeepHiddenProblemPrivileges() {
        prepare("ARCHIVED","PRIVATE",null);
        for(var status:List.of(UserStatus.BANNED,UserStatus.STOPPED,UserStatus.TIMEOUT))
            assertEquals(404,assertThrows(ResponseStatusException.class,
                ()->problems.load(1001,user(8,Role.OPERATOR,status))).getStatusCode().value());
        verifyNoInteractions(contests);
    }

    @Test void onlyPublishedPublicProblemsAreAnonymousReadable() {
        for(var status:List.of("DRAFT","PENDING_REVIEW","REJECTED","ARCHIVED")) {
            prepare(status,"PUBLIC",null);
            assertEquals(404,assertThrows(ResponseStatusException.class,()->problems.load(1001,null)).getStatusCode().value());
        }
        prepare("PUBLISHED","PUBLIC",null);
        assertDoesNotThrow(()->problems.load(1001,null));
    }

    @Test void currentGroupPermissionGrantsReadAndItsRevocationImmediatelyRemovesIt() {
        prepare("PUBLISHED","GROUP",9L);
        var viewer=user(8,Role.USER,UserStatus.ACTIVE);
        when(groups.can(9L,viewer,GroupPermission.VIEW_PROBLEMS)).thenReturn(true);
        assertDoesNotThrow(()->problems.load(1001,viewer));
        when(groups.can(9L,viewer,GroupPermission.VIEW_PROBLEMS)).thenReturn(false);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->problems.load(1001,viewer)).getStatusCode().value());
    }

    @Test void authorizedGroupReviewerRetainsHiddenDraftAccessWithoutMakingItPublic() {
        prepare("DRAFT","GROUP",9L);
        var reviewer=user(8,Role.USER,UserStatus.ACTIVE);
        when(groups.can(9L,reviewer,GroupPermission.REVIEW_PROBLEMS)).thenReturn(true);
        assertDoesNotThrow(()->problems.load(1001,reviewer));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->problems.load(1001,null)).getStatusCode().value());
    }

    private void prepare(String status,String visibility,Long groupId) {
        Map<String,Object> problem=new HashMap<>();
        problem.put("id",77L);problem.put("author_id",5L);problem.put("status",status);
        problem.put("visibility",visibility);problem.put("group_id",groupId);
        when(db.queryForList("SELECT * FROM problems WHERE problem_number=?",1001)).thenReturn(List.of(problem));
    }
    private static CustomUserDetails user(long id,Role role,UserStatus status) {
        var user=User.builder().username("policy-test").password("unused").build();
        ReflectionTestUtils.setField(user,"id",id);ReflectionTestUtils.setField(user,"role",role);ReflectionTestUtils.setField(user,"status",status);
        return new CustomUserDetails(user);
    }
}
