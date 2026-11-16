package com.drewdrew0414.global.security.jwt;

import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.UserStatus;
import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.global.security.CustomUserDetailsService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 인증 저장소 장애는 503으로 종료하고 잘못된 토큰은 기존 익명 경로를 유지합니다. */
class JwtAuthenticationFailureTest {
    private final JwtTokenProvider provider=mock(JwtTokenProvider.class);
    private final CustomUserDetailsService users=mock(CustomUserDetailsService.class);
    private final JwtAuthenticationFilter filter=new JwtAuthenticationFilter(provider,users);

    @AfterEach void clear() {SecurityContextHolder.clearContext();}

    @Test void databaseFailureIsNotDowngradedToAnonymousAndDoesNotExposeSqlOrIdentity() throws Exception {
        var request=request();var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        when(provider.getClaims("token")).thenReturn(Jwts.claims().subject("1").add("username","private-username").build());
        when(users.loadUserByUsername("private-username"))
            .thenThrow(new TransientDataAccessResourceException("SELECT password secret-SQL-must-not-leak"));
        filter.doFilter(request,response,chain);
        assertEquals(503,response.getStatus());assertTrue(response.getContentType().startsWith("application/json"));
        assertTrue(response.getContentAsString().contains("SERVICE_UNAVAILABLE"));
        assertFalse(response.getContentAsString().contains("secret-SQL"));assertFalse(response.getContentAsString().contains("private-username"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());verifyNoInteractions(chain);
    }

    @Test void invalidJwtStillContinuesAsAnonymousForPublicEndpoints() throws Exception {
        var request=request();var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        when(provider.getClaims("token")).thenThrow(new MalformedJwtException("private-token"));
        filter.doFilter(request,response,chain);
        assertEquals(200,response.getStatus());assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(chain).doFilter(request,response);verifyNoInteractions(users);
    }

    @Test void stoppedAccountCannotAuthenticateEvenWithACurrentSignedToken() throws Exception {
        var request=request();var response=new MockHttpServletResponse();var chain=mock(FilterChain.class);
        var user=User.builder().username("stopped-user").password("unused").build();
        ReflectionTestUtils.setField(user,"id",1L);ReflectionTestUtils.setField(user,"status",UserStatus.STOPPED);
        when(provider.getClaims("token")).thenReturn(Jwts.claims().subject("1")
            .add("username","stopped-user").add("tokenVersion",0).build());
        when(users.loadUserByUsername("stopped-user")).thenReturn(new CustomUserDetails(user));
        filter.doFilter(request,response,chain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());verify(chain).doFilter(request,response);
    }

    private static MockHttpServletRequest request() {
        var request=new MockHttpServletRequest("GET","/api/problems/1000");request.addHeader("Authorization","Bearer token");return request;
    }
}
