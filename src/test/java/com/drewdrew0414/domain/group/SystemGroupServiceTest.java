package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 이름이 같은 일반 그룹과 불변 키를 가진 시스템 그룹을 구분하고 우회 권한을 검사합니다. */
class SystemGroupServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final GroupService groups=new GroupService(db);

    @Test void defaultMembershipCannotBeLeftOrRemovedEvenByAnOperator() {
        systemGroup();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->groups.leave(9,actor(true))).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->groups.removeMember(9,7,actor(true))).getStatusCode().value());
        verify(db,never()).update(startsWith("DELETE FROM group_members"),any(Object[].class));
    }

    @Test void defaultNameIsImmutableButAnOperatorCanUpdateItsDescription() {
        systemGroup();
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.update(9,new GroupRequests.Write("Hijacked","changed"),actor(true))).getStatusCode().value());
        groups.update(9,new GroupRequests.Write("COBWEB GROUP"," updated "),actor(true));
        verify(db).update("UPDATE study_groups SET group_name=?,group_description=? WHERE id=?","COBWEB GROUP","updated",9L);
    }

    @Test void delegatedOrdinaryManagerCannotManageSystemRolesOrPromoteMembers() {
        systemGroup();
        var manager=actor(false);
        Map<String,Object> role=new HashMap<>();
        for(var permission:GroupPermission.values())role.put(permission.column(),true);
        when(db.queryForList(contains("SELECT gr.* FROM group_members gm"),eq(9L),eq(42L))).thenReturn(List.of(role));
        var request=new GroupRequests.RoleWrite("custom",Set.of(GroupPermission.VIEW_PROBLEMS));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.createRole(9,request,manager)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.updateRole(9,8,request,manager)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.deleteRole(9,8,manager)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->groups.changeMemberRole(9,7,8,manager)).getStatusCode().value());
        verify(db,never()).update(anyString(),any(Object[].class));
    }

    @Test void operatorStillCannotAssignTheUnownedSystemOwnerRole() {
        systemGroup();
        when(db.queryForList(contains("FROM group_members gm JOIN group_roles"),eq(9L),eq(7L)))
            .thenReturn(List.of(Map.of("user_id",7L,"permission","MEMBER","builtin_key","MEMBER")));
        when(db.queryForList("SELECT * FROM group_roles WHERE group_id=? AND id=?",9L,8L))
            .thenReturn(List.of(Map.of("id",8L,"builtin_key","OWNER")));
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.changeMemberRole(9,7,8,actor(true))).getStatusCode().value());
        verify(db,never()).update(startsWith("UPDATE group_members"),any(Object[].class));
    }

    @Test void missingSystemMembershipRepairsToMemberWithoutUsingNormalQuotaOrMaxPlusOne() {
        systemGroup();
        when(db.queryForList(contains("FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE"),eq(42L)))
            .thenReturn(List.of(Map.of("id",42L)));
        missingMembershipCount();
        when(db.queryForObject("SELECT id FROM group_roles WHERE group_id=? AND builtin_key=?",Long.class,9L,"MEMBER"))
            .thenReturn(8L);
        groups.join(9,actor(false));
        verify(db).update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,?,'MEMBER',?)",
            42L,9L,42L,8L);
        verify(db,never()).queryForObject(contains("g.system_key IS NULL"),eq(Long.class),any());
        verify(db,never()).queryForObject(contains("MAX(member_number)"),eq(Long.class),any());
    }

    @Test void normalJoinCountsOnlyNormalGroupsAndUsesLongMemberNumbers() {
        when(db.queryForList(contains("FROM study_groups"),eq(9L)))
            .thenReturn(List.of(Map.of("id",9L,"status","ACCEPTED","group_name","COBWEB GROUP")));
        when(db.queryForList(contains("FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE"),eq(42L)))
            .thenReturn(List.of(Map.of("id",42L)));
        missingMembershipCount();
        when(db.queryForObject(contains("g.system_key IS NULL"),eq(Long.class),eq(42L))).thenReturn(2L);
        when(db.queryForObject(contains("MAX(member_number)"),eq(Long.class),eq(9L))).thenReturn(2147483648L);
        when(db.queryForObject("SELECT id FROM group_roles WHERE group_id=? AND builtin_key=?",Long.class,9L,"MEMBER"))
            .thenReturn(8L);
        groups.join(9,actor(false));
        verify(db).update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,?,'MEMBER',?)",
            42L,9L,2147483648L,8L);
    }

    @Test void publicSummaryFlagUsesTheKeyNotTheDisplayName() {
        when(db.queryForList(anyString(),eq("%%"),eq(0))).thenReturn(List.of(
            Map.of("id",9L,"group_name","COBWEB GROUP","member_count",2L,"system_key",GroupService.SYSTEM_GROUP_KEY),
            Map.of("id",10L,"group_name","COBWEB GROUP","member_count",1L)));
        var result=groups.publicList(0,"");
        assertEquals(true,result.get(0).get("systemGroup"));
        assertEquals(false,result.get(1).get("systemGroup"));
    }

    private void missingMembershipCount() {
        // join은 ACTIVE 계정 행 잠금과 ACCEPTED 그룹 확인을 먼저 수행합니다.
        // requireMember의 JOIN형 쿼리에도 동일 fixture가 적용되며 상태 조건을 빠뜨릴 수 없습니다.
        when(db.queryForObject(contains("SELECT COUNT(*) FROM group_members"),eq(Long.class),eq(9L),eq(42L)))
            .thenAnswer(invocation->{
                String sql=invocation.getArgument(0);
                if(sql.contains("JOIN study_groups")) {
                    assertTrue(sql.contains("g.status='ACCEPTED'"));
                    assertTrue(sql.contains("u.status='ACTIVE'"));
                }
                return 0L;
            });
    }

    private void systemGroup() {
        when(db.queryForList(contains("FROM study_groups"),eq(9L))).thenReturn(List.of(
            Map.of("id",9L,"group_name","COBWEB GROUP","status","ACCEPTED","system_key",GroupService.SYSTEM_GROUP_KEY)));
    }
    private static CustomUserDetails actor(boolean operator) {
        var actor=mock(CustomUserDetails.class);
        when(actor.isEnabled()).thenReturn(true);when(actor.getUserId()).thenReturn(42L);
        when(actor.getAuthorities()).thenAnswer(invocation->List.of(new SimpleGrantedAuthority(operator?"ROLE_OPERATOR":"ROLE_USER")));
        return actor;
    }
}
