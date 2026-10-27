package com.drewdrew0414;

import com.drewdrew0414.domain.group.*;
import com.drewdrew0414.domain.user.dto.request.SignupRequest;
import com.drewdrew0414.domain.user.entity.*;
import com.drewdrew0414.domain.user.repository.UserRepository;
import com.drewdrew0414.domain.user.service.UserService;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.EmailCodeHash;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 별도 test 프로필 DB에서만 실행하는 내장 그룹 생성·가입·원자성·권한 회귀입니다. 외부 OAuth/메일은 호출하지 않습니다. */
@SpringBootTest
@ActiveProfiles("test")
class SystemGroupIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired GroupService groups;
    @Autowired UserService signup;
    @Autowired UserRepository users;
    @Autowired EmailCodeHash hashes;
    @Autowired PlatformTransactionManager transactions;

    @Test void migrationCreatesExactlyOneUnownedSystemGroupAndBackfillsEveryAccountAsAMember() {
        long gid=systemId();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM study_groups WHERE system_key IS NOT NULL",Integer.class));
        assertEquals("COBWEB GROUP",db.queryForObject("SELECT group_name FROM study_groups WHERE id=?",String.class,gid));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM group_roles WHERE group_id=?",Integer.class,gid));
        assertEquals(0,db.queryForObject("""
            SELECT COUNT(*) FROM group_members gm JOIN group_roles r ON r.group_id=gm.group_id AND r.id=gm.role_id
            WHERE gm.group_id=? AND (gm.permission='OWNER' OR r.builtin_key='OWNER')
            """,Integer.class,gid));
        assertEquals(0,db.queryForObject("""
            SELECT COUNT(*) FROM users u WHERE NOT EXISTS
                (SELECT 1 FROM group_members gm WHERE gm.group_id=? AND gm.user_id=u.id)
            """,Integer.class,gid));
        assertEquals("bigint",db.queryForObject("""
            SELECT DATA_TYPE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='group_members' AND COLUMN_NAME='member_number'
            """,String.class));
    }

    @Test void localSignupAndAllowedOAuthRepositoryInsertsGetOnlyTheMemberRole() {
        String username=name(),email=username+"@example.invalid",code="12840379";
        db.update("INSERT INTO email_verifications(email,code_hash,purpose,is_verified,expires_at) VALUES(?,?,'JOIN',TRUE,?)",
            email,hashes.hash(email,VerificationPurpose.JOIN,code),LocalDateTime.now().plusMinutes(5));
        var request=new SignupRequest();
        ReflectionTestUtils.setField(request,"username",username);
        ReflectionTestUtils.setField(request,"email",email);
        ReflectionTestUtils.setField(request,"password","system-group-test-only-password");
        ReflectionTestUtils.setField(request,"verificationCode",code);
        assertMember(signup.signup(request));
        for(Provider provider:List.of(Provider.GOOGLE,Provider.GITHUB)) {
            var social=users.saveAndFlush(User.builder().username(name()).email(name()+"@example.invalid")
                .provider(provider).providerId(UUID.randomUUID().toString()).password("not-a-login-secret").build());
            assertMember(social.getId());
        }
    }

    @Test void defaultGroupIsOutsideTheThreeNormalGroupQuotaAndDoesNotGrantOrdinaryManagement() {
        var owner=account(Role.USER,50);
        for(int index=0;index<3;index++)groups.create(new GroupRequests.Write(name(),"quota test"),owner);
        assertEquals(4,groups.mine(owner).size());
        assertEquals(3,groups.mine(owner).stream().filter(group->!Boolean.TRUE.equals(group.get("systemGroup"))).count());
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.create(new GroupRequests.Write(name(),"fourth normal group"),owner)).getStatusCode().value());
        long gid=systemId();
        assertTrue(groups.can(gid,owner,GroupPermission.VIEW_PROBLEMS));
        assertTrue(groups.can(gid,owner,GroupPermission.TAKE_CONTESTS));
        assertFalse(groups.can(gid,owner,GroupPermission.MANAGE_MEMBERS));
        assertFalse(groups.can(gid,owner,GroupPermission.MANAGE_ROLES));
        assertFalse(groups.can(gid,owner,GroupPermission.CREATE_PROBLEMS));
        var operator=account(Role.OPERATOR,0);
        assertTrue(groups.can(gid,operator,GroupPermission.CREATE_PROBLEMS));
        assertTrue(groups.can(gid,operator,GroupPermission.REVIEW_PROBLEMS));
        assertTrue(groups.can(gid,operator,GroupPermission.MANAGE_CONTESTS));
        assertTrue(groups.can(gid,operator,GroupPermission.TAKE_CONTESTS));
    }

    @Test
    @Transactional
    void apiAndDatabaseBothProtectSystemIdentityMembershipAndUnownedRole() {
        long gid=systemId();
        var member=account(Role.USER,0);
        var operator=account(Role.OPERATOR,0);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->groups.leave(gid,member)).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.removeMember(gid,member.getUserId(),operator)).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.update(gid,new GroupRequests.Write("changed","test"),operator)).getStatusCode().value());
        long ownerRole=db.queryForObject("SELECT id FROM group_roles WHERE group_id=? AND builtin_key='OWNER'",Long.class,gid);
        assertEquals(409,assertThrows(ResponseStatusException.class,
            ()->groups.changeMemberRole(gid,member.getUserId(),ownerRole,operator)).getStatusCode().value());
        assertThrows(DataAccessException.class,()->db.update("UPDATE study_groups SET system_key=NULL WHERE id=?",gid));
        assertThrows(DataAccessException.class,()->db.update("UPDATE study_groups SET group_name='changed' WHERE id=?",gid));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM study_groups WHERE id=?",gid));
        assertThrows(DataAccessException.class,()->db.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",gid,member.getUserId()));
        assertThrows(DataAccessException.class,()->db.update("UPDATE group_members SET permission='OWNER',role_id=? WHERE group_id=? AND user_id=?",
            ownerRole,gid,member.getUserId()));
        assertMember(member.getUserId());
    }

    @Test void accountAndMembershipCommitOrRollbackTogetherAndMembershipFailureRejectsTheAccount() {
        String username=name();
        Long rolledBack=new TransactionTemplate(transactions).execute(status->{
            db.update("INSERT INTO users(username,password) VALUES(?,'test-only')",username);
            long uid=db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);
            assertMember(uid);
            status.setRollbackOnly();
            return uid;
        });
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,rolledBack));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM group_members WHERE user_id=?",Integer.class,rolledBack));
        // users 자체는 음수 BIGINT PK를 허용하지만 회원 번호는 양수 제약이므로 트리거의 가입 단계에서 실패합니다.
        long invalidUid=-1L-Math.abs((long)UUID.randomUUID().hashCode());
        assertThrows(DataAccessException.class,()->db.update("INSERT INTO users(id,username,password) VALUES(?,?,'test-only')",invalidUid,name()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM users WHERE id=?",Integer.class,invalidUid));
    }

    @Test void concurrentAccountInsertsDoNotRaceOnTheDefaultMemberNumber() throws Exception {
        List<Future<Long>> inserts=new ArrayList<>();
        try(var executor=Executors.newFixedThreadPool(4)) {
            for(int index=0;index<8;index++)inserts.add(executor.submit(()->{
                String username=name();
                db.update("INSERT INTO users(username,password) VALUES(?,'test-only')",username);
                return db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username);
            }));
            var seen=new HashSet<Long>();
            for(var insert:inserts) {
                long uid=insert.get(15,TimeUnit.SECONDS);
                assertTrue(seen.add(uid));assertMember(uid);
            }
        }
    }

    private long systemId() {
        return db.queryForObject("SELECT id FROM study_groups WHERE system_key=?",Long.class,GroupService.SYSTEM_GROUP_KEY);
    }
    private void assertMember(long uid) {
        var row=db.queryForMap("""
            SELECT gm.permission,gm.member_number,r.builtin_key FROM group_members gm
            JOIN study_groups g ON g.id=gm.group_id JOIN group_roles r ON r.group_id=gm.group_id AND r.id=gm.role_id
            WHERE gm.user_id=? AND g.system_key=?
            """,uid,GroupService.SYSTEM_GROUP_KEY);
        assertEquals("MEMBER",row.get("permission"));assertEquals("MEMBER",row.get("builtin_key"));
        assertEquals(uid,((Number)row.get("member_number")).longValue());
    }
    private CustomUserDetails account(Role role,int solved) {
        String username=name();
        db.update("INSERT INTO users(username,password,role,solved_count) VALUES(?,'test-only',?,?)",username,role.name(),solved);
        var user=User.builder().username(username).password("test-only").build();
        ReflectionTestUtils.setField(user,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,username));
        ReflectionTestUtils.setField(user,"role",role);
        return new CustomUserDetails(user);
    }
    private static String name() { return "s"+UUID.randomUUID().toString().replace("-","").substring(0,14); }
}
