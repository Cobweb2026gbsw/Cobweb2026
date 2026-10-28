package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.RefreshToken;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.exception.InvalidTokenException;
import com.drewdrew0414.domain.user.repository.RefreshTokenRepository;
import com.drewdrew0414.domain.user.repository.UserLogRepository;
import com.drewdrew0414.domain.user.repository.UserRepository;
import com.drewdrew0414.global.security.TokenHash;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 실제 DB/브라우저 없이 쿠키 속성과 서명된 유지 정책의 로그인·회전·로그아웃 계약을 확인합니다. */
@ExtendWith(MockitoExtension.class)
class RememberMeCookieTest {
    private static final String SECRET = "test-only-remember-me-secret-0123456789abcdef";
    private static final long TTL = 1_209_600_000L;
    @Mock UserRepository users;
    @Mock UserLogRepository logs;
    @Mock RefreshTokenRepository refresh;
    @Mock PasswordEncoder passwords;
    @Mock AuthRateLimitService limits;
    private JwtTokenProvider jwt;
    private AuthService service;
    private User user;

    @BeforeEach void prepare() {
        jwt = new JwtTokenProvider(SECRET, 1_800_000L, TTL);
        service = new AuthService(users, logs, refresh, passwords, jwt, limits);
        user = User.builder().username("sample-user").password("hashed-password").build();
        ReflectionTestUtils.setField(user, "id", 7L);
    }

    private String cookieToken(MockHttpServletResponse response) {
        String cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie);
        return cookie.substring("refreshToken=".length()).split(";", 2)[0];
    }

    private void assertPolicy(MockHttpServletResponse response, boolean remember) {
        String cookie = response.getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=/"));
        assertEquals(remember, cookie.contains("Max-Age=1209600"));
        assertEquals(remember, cookie.contains("Expires="));
        assertEquals(remember, jwt.isPersistentRefreshToken(cookieToken(response)));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void loginUsesExplicitPersistenceWithoutLoweringCookieSecurity(boolean remember) {
        when(users.findLockedByUsername("sample-user")).thenReturn(Optional.of(user));
        when(passwords.matches("plain-test-password", "hashed-password")).thenReturn(true);
        var response = new MockHttpServletResponse();

        var result = service.login("sample-user", "plain-test-password", "192.0.2.1", "test-agent", remember, response);

        assertNotNull(result.getAccessToken());
        assertPolicy(response, remember);
        var saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refresh).save(saved.capture());
        assertEquals(TokenHash.sha256(cookieToken(response)), saved.getValue().getToken());
        assertNotEquals(cookieToken(response), saved.getValue().getToken());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void refreshRotationPreservesPersistenceAndInvalidatesThePreviousToken(boolean remember) {
        String old = jwt.generateRefreshToken(7L, remember);
        when(users.findLockedById(7L)).thenReturn(Optional.of(user));
        when(refresh.findByToken(TokenHash.sha256(old))).thenReturn(Optional.of(
                new RefreshToken(7L, TokenHash.sha256(old), LocalDateTime.now().plusDays(1))));
        var response = new MockHttpServletResponse();

        service.reissue(old, response);

        assertPolicy(response, remember);
        assertNotEquals(old, cookieToken(response));
        verify(refresh).deleteByUserId(7L);
        verify(refresh).flush();
    }

    @Test void legacyRefreshWithoutPreferenceKeepsItsExistingPersistentPolicy() {
        String legacy = Jwts.builder().subject("7").expiration(new Date(System.currentTimeMillis() + TTL))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes())).compact();
        assertTrue(jwt.readRefreshToken(legacy).rememberMe());
        assertEquals(7L, jwt.validateAndGetUserId(legacy));
    }

    @Test void oauthIssuanceRetainsItsExistingPersistencePolicy() {
        var response = new MockHttpServletResponse();
        service.issueTokens(user, response);
        assertPolicy(response, true);
    }

    @Test void logoutClearsTheSameSecureCookieAndServerRefreshRecord() {
        when(users.findLockedById(7L)).thenReturn(Optional.of(user));
        var response = new MockHttpServletResponse();
        service.logout(7L, response);
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
        assertTrue(response.getHeader("Set-Cookie").contains("HttpOnly"));
        verify(refresh).deleteByUserId(7L);
        assertEquals(1L, user.getAccessTokenVersion());
    }

    @Test void tamperingWithPersistenceNeverReachesDatabaseLookup() {
        String token = jwt.generateRefreshToken(7L, false);
        String[] parts = token.split("\\.");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        parts[1] = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replace("false", "true").getBytes());
        assertThrows(InvalidTokenException.class, () -> service.reissue(String.join(".", parts), new MockHttpServletResponse()));
        verifyNoInteractions(users, refresh);
    }
}
