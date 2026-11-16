package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 소속·역할만 남은 정지 계정/미승인 그룹은 일반 권한을 복구하지 않습니다. */
class GroupAuthorizationConditionsTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final GroupService groups=new GroupService(db);

    @Test void rolePermissionsAreJoinedToCurrentAcceptedGroupAndActiveAccount() {
        var member=actor(true,false);
        when(db.queryForList(contains("SELECT gr.* FROM group_members gm"),eq(9L),eq(42L)))
            .thenAnswer(invocation->{
                String sql=invocation.getArgument(0);
                assertTrue(sql.contains("g.id=gm.group_id AND g.status='ACCEPTED'"));
                assertTrue(sql.contains("u.id=gm.user_id AND u.status='ACTIVE'"));
                return List.of(Map.of("can_view_problems",true,"can_take_contests",true));
            });
        assertTrue(groups.can(9,member,GroupPermission.VIEW_PROBLEMS));
        assertTrue(groups.can(9,member,GroupPermission.TAKE_CONTESTS));
    }

    @Test void rejectedOrWaitingGroupMembershipCannotReadRolesOrMembers() {
        var member=actor(true,false);
        when(db.queryForObject(contains("SELECT COUNT(*) FROM group_members gm"),eq(Long.class),eq(9L),eq(42L)))
            .thenAnswer(invocation->{
                String sql=invocation.getArgument(0);
                assertTrue(sql.contains("g.status='ACCEPTED'"));
                assertTrue(sql.contains("u.status='ACTIVE'"));
                return 0L;
            });
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.roles(9,member)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.members(9,member)).getStatusCode().value());
        verify(db,never()).queryForList(contains("SELECT * FROM group_roles"),any(Object[].class));
    }

    @Test void anActiveMemberStillReadsRolesWhenTheJoinedConditionsMatch() {
        var member=actor(true,false);
        when(db.queryForObject(contains("SELECT COUNT(*) FROM group_members gm"),eq(Long.class),eq(9L),eq(42L)))
            .thenReturn(1L);
        assertEquals(List.of(),groups.roles(9,member));
        verify(db).queryForList("SELECT * FROM group_roles WHERE group_id=? ORDER BY id",9L);
    }

    @Test void platformOperatorMayInspectButTakingAnExamStillRequiresAnAcceptedMembership() {
        var operator=actor(true,true);
        groups.requireMember(9,operator);
        assertTrue(groups.can(9,operator,GroupPermission.MANAGE_ROLES));
        verifyNoInteractions(db);
        assertFalse(groups.can(9,operator,GroupPermission.TAKE_CONTESTS));
        verify(db).queryForList(argThat(sql->sql.contains("g.status='ACCEPTED'")&&sql.contains("u.status='ACTIVE'")),eq(9L),eq(42L));
    }

    @Test void disabledActorsCannotProbeGroupExistenceThroughMutations() {
        var disabled=actor(false,true);
        List<Runnable> attempts=List.of(
            ()->groups.update(9,null,disabled),
            ()->groups.changeMemberRole(9,7,3,disabled),
            ()->groups.removeMember(9,7,disabled),
            ()->groups.createRole(9,null,disabled),
            ()->groups.updateRole(9,3,null,disabled),
            ()->groups.deleteRole(9,3,disabled));
        for(var attempt:attempts)
            assertEquals(401,assertThrows(ResponseStatusException.class,attempt::run).getStatusCode().value());
        assertFalse(groups.can(9,disabled,GroupPermission.MANAGE_GROUP));
        verifyNoInteractions(db);
    }

    @Test void anonymousActorsCannotProbeGroupExistenceThroughMutations() {
        assertEquals(401,assertThrows(ResponseStatusException.class,()->groups.update(9,null,null)).getStatusCode().value());
        assertEquals(401,assertThrows(ResponseStatusException.class,()->groups.createRole(9,null,null)).getStatusCode().value());
        verifyNoInteractions(db);
    }

    private static CustomUserDetails actor(boolean enabled,boolean operator) {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(enabled);when(user.getUserId()).thenReturn(42L);
        when(user.getAuthorities()).thenAnswer(invocation->List.of(new SimpleGrantedAuthority(operator?"ROLE_OPERATOR":"ROLE_USER")));
        return user;
    }
}
