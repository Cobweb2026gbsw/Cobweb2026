package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupServiceTest {
    @Test void owningAndJoiningAlreadyThreeGroupsBlocksAnotherCreation() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(42L);
        when(db.queryForList(contains("FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE"),eq(42L)))
            .thenReturn(List.of(Map.of("solved_count",50)));
        when(db.queryForObject(contains("COUNT(*) FROM group_members WHERE user_id=?"),eq(Long.class),eq(42L)))
            .thenReturn(3L);

        var error=assertThrows(ResponseStatusException.class,
            () -> groups.create(new GroupRequests.Write("새 그룹","설명"),user));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO study_groups"),any(Object[].class));
    }

    @Test void operatorCanManageButCannotTakeAContestWithoutAnActualMemberRole() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        var operator=operator();
        when(db.queryForList(contains("FROM group_members gm"),eq(9L),eq(42L))).thenReturn(List.of());

        assertTrue(groups.can(9,operator,GroupPermission.MANAGE_ROLES));
        assertFalse(groups.can(9,operator,GroupPermission.TAKE_CONTESTS));
    }

    @Test void roleLimitIsCheckedBeforeAddingANinthRole() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        when(db.queryForList(contains("FROM study_groups"),eq(9L)))
            .thenReturn(List.of(Map.of("id",9L)));
        when(db.queryForObject(contains("COUNT(*) FROM group_roles"),eq(Long.class),eq(9L)))
            .thenReturn(8L);

        var error=assertThrows(ResponseStatusException.class,() -> groups.createRole(9,
            new GroupRequests.RoleWrite("추가 역할",Set.of(GroupPermission.VIEW_PROBLEMS)),operator()));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO group_roles"),any(Object[].class));
    }

    @Test void anOwnerCannotBeDemotedEvenByAnOperator() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        when(db.queryForList(contains("FROM study_groups"),eq(9L)))
            .thenReturn(List.of(Map.of("id",9L)));
        when(db.queryForList(contains("FROM group_members gm JOIN group_roles"),eq(9L),eq(7L)))
            .thenReturn(List.of(Map.of("user_id",7L,"permission","OWNER","builtin_key","OWNER")));

        var error=assertThrows(ResponseStatusException.class,
            () -> groups.changeMemberRole(9,7,8,operator()));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(db,never()).update(startsWith("UPDATE group_members"),any(Object[].class));
    }

    @Test void roleManagerCannotCreateARoleWithPermissionsTheyDoNotHave() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        var manager=roleManager(db);

        var error=assertThrows(ResponseStatusException.class,() -> groups.createRole(9,
            new GroupRequests.RoleWrite("승격 역할",Set.of(GroupPermission.MANAGE_ROLES,
                GroupPermission.VIEW_CONTEST_RESULTS)),manager));

        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verify(db,never()).update(startsWith("INSERT INTO group_roles"),any(Object[].class));
    }

    @Test void roleManagerCannotUpgradeTheirOwnRoleToGainOtherPermissions() {
        var db=mock(JdbcTemplate.class);
        var groups=new GroupService(db);
        var manager=roleManager(db);
        when(db.queryForList("SELECT * FROM group_roles WHERE group_id=? AND id=?",9L,7L))
            .thenReturn(List.of(Map.of("id",7L,"role_name","커스텀")));

        var error=assertThrows(ResponseStatusException.class,() -> groups.updateRole(9,7,
            new GroupRequests.RoleWrite("커스텀",Set.of(GroupPermission.MANAGE_ROLES,
                GroupPermission.MANAGE_MEMBERS)),manager));

        assertEquals(HttpStatus.FORBIDDEN,error.getStatusCode());
        verify(db,never()).update(startsWith("UPDATE group_roles SET"),any(Object[].class));
    }

    private static CustomUserDetails roleManager(JdbcTemplate db) {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(42L);
        when(user.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority("ROLE_USER")));
        when(db.queryForList(contains("FROM study_groups"),eq(9L)))
            .thenReturn(List.of(Map.of("id",9L)));
        when(db.queryForList(contains("SELECT gr.* FROM group_members gm"),eq(9L),eq(42L)))
            .thenReturn(List.of(Map.of("can_manage_roles",true)));
        return user;
    }

    private static CustomUserDetails operator() {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(42L);
        when(user.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        return user;
    }
}
