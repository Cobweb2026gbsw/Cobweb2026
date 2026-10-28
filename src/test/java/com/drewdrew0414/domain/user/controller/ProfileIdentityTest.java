package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.UserStatus;
import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 공개 프로필의 익명성은 유지하고 내 계정에만 초안 분리용 불변 ID를 제공합니다. */
class ProfileIdentityTest {
    @Test void publicUsernamePathNeverIncludesAccountIdAndOnlyLooksUpPublicSolvedProblems() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        when(db.queryForList(contains("FROM users WHERE lower(username)=lower(?)"),eq("alice"))).thenReturn(List.of(row(7L,"alice")));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(7L))).thenReturn(solved());
        var result=data(controller.profile("alice"));
        assertPublic(result);assertEquals("alice",result.get("username"));assertEquals(solved(),result.get("solvedProblems"));
        verify(db).queryForList(contains("p.status='PUBLISHED' AND p.visibility='PUBLIC'"),eq(7L));
    }
    @ParameterizedTest @ValueSource(strings={"..","a/b","기존사용자"})
    void publicQueryAliasKeepsTheExactUsernameButDoesNotExposeStableIdentity(String username) {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        when(db.queryForList(contains("FROM users WHERE lower(username)=lower(?)"),eq(username))).thenReturn(List.of(row(12L,username)));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(12L))).thenReturn(solved());
        var result=data(controller.profileQuery(username));
        assertEquals(username,result.get("username"));assertPublic(result);
        verify(db).queryForList(contains("FROM users WHERE lower(username)=lower(?)"),eq(username));
    }
    @Test void meLoadsOnlyTheAuthenticatedImmutableIdInsteadOfAnEqualOrReusedUsername() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);var principal=principal(41L,"old-name",UserStatus.ACTIVE);
        when(db.queryForList(contains("FROM users WHERE id=?"),eq(41L))).thenReturn(List.of(row(41L,"current-name")));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(41L))).thenReturn(solved());
        var result=data(controller.me(principal));
        assertEquals("41",result.get("id"));assertEquals("current-name",result.get("username"));
        verify(db).queryForList(contains("FROM users WHERE id=?"),eq(41L));
        verify(db,never()).queryForList(contains("FROM users WHERE lower(username)=lower(?)"),anyString());
        for(String privateField:List.of("email","password","role","provider_id","accessToken","refreshToken"))
            assertFalse(result.containsKey(privateField),privateField);
    }
    @Test void meUsesThePrincipalStableIdNotAnUnrelatedSelectedRowValue() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        // DB predicate is principal-bound; even a malformed mocked projection cannot choose the cache owner ID.
        when(db.queryForList(contains("FROM users WHERE id=?"),eq(41L))).thenReturn(List.of(row(999L,"alice")));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(999L))).thenReturn(List.of());
        assertEquals("41",data(controller.me(principal(41L,"alice",UserStatus.ACTIVE))).get("id"));
        verify(db,never()).queryForList(contains("FROM users WHERE lower(username)=lower(?)"),anyString());
    }
    @Test void readingMeDoesNotAddPrivateIdToLaterPublicPathOrQueryResponses() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        when(db.queryForList(contains("FROM users WHERE id=?"),eq(41L))).thenAnswer(call->List.of(row(41L,"alice")));
        when(db.queryForList(contains("FROM users WHERE lower(username)=lower(?)"),eq("alice"))).thenAnswer(call->List.of(row(41L,"alice")));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(41L))).thenReturn(solved());
        assertEquals("41",data(controller.me(principal(41L,"alice",UserStatus.ACTIVE))).get("id"));
        assertPublic(data(controller.profile("alice")));assertPublic(data(controller.profileQuery("alice")));
    }
    @Test void mePreservesTheLargestDatabaseBigintAsAnExactDecimalStringInJson() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        when(db.queryForList(contains("FROM users WHERE id=?"),eq(Long.MAX_VALUE))).thenReturn(List.of(row(Long.MAX_VALUE,"alice")));
        when(db.queryForList(contains("FROM user_solved_problems"),eq(Long.MAX_VALUE))).thenReturn(List.of());
        Object response=controller.me(principal(Long.MAX_VALUE,"alice",UserStatus.ACTIVE));
        var result=data(response);
        assertEquals("9223372036854775807",assertInstanceOf(String.class,result.get("id")));
        var json=new tools.jackson.databind.ObjectMapper();
        var id=json.readTree(json.writeValueAsString(response)).get("data").get("id");
        assertTrue(id.isString());assertEquals("9223372036854775807",id.stringValue());
        verify(db).queryForList(contains("FROM users WHERE id=?"),eq(Long.MAX_VALUE));
    }
    @Test void anonymousMeIsRejectedWithoutAnyProfileLookup() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.me(null)).getStatusCode().value());
        verifyNoInteractions(db);
    }
    @ParameterizedTest @EnumSource(value=UserStatus.class,names="ACTIVE",mode=EnumSource.Mode.EXCLUDE)
    void inactiveAccountCannotReadItsCacheOwnerId(UserStatus status) {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        assertEquals(401,assertThrows(ResponseStatusException.class,()->controller.me(principal(41L,"alice",status))).getStatusCode().value());
        verifyNoInteractions(db);
    }
    @Test void missingPrivateOrPublicProfileRemainsNotFoundInsteadOfFabricatingIdentity() {
        var db=mock(JdbcTemplate.class);var controller=new ProfileController(db);
        when(db.queryForList(contains("FROM users WHERE id=?"),eq(41L))).thenReturn(List.of());
        when(db.queryForList(contains("FROM users WHERE lower(username)=lower(?)"),eq("alice"))).thenReturn(List.of());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.me(principal(41L,"alice",UserStatus.ACTIVE))).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.profile("alice")).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controller.profileQuery("alice")).getStatusCode().value());
        verify(db,never()).queryForList(contains("FROM user_solved_problems"),anyLong());
    }
    private static void assertPublic(Map<String,Object> profile) {
        for(String privateField:List.of("id","userId","user_id","email","password","role","provider_id","accessToken","refreshToken"))
            assertFalse(profile.containsKey(privateField),privateField);
    }
    private static Map<String,Object> row(long id,String username) {
        var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("username",username);
        result.put("bio","public bio");result.put("github_url","https://github.com/example");
        result.put("solved_count",1);result.put("submit_count",2);result.put("rank_type","INITIATE");result.put("rank_int",5);
        return result;
    }
    private static List<Map<String,Object>> solved(){return List.of(Map.of("problem_number",1000,"problem_title","Hello World!"));}
    private static CustomUserDetails principal(long id,String username,UserStatus status) {
        var entity=User.builder().username(username).password("profile-identity-test-only").build();
        ReflectionTestUtils.setField(entity,"id",id);ReflectionTestUtils.setField(entity,"status",status);
        return new CustomUserDetails(entity);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> data(Object response) {
        var envelope=assertInstanceOf(ApiResponse.class,response);assertTrue(envelope.isSuccess());
        return (Map<String,Object>)assertInstanceOf(Map.class,envelope.getData());
    }
}
