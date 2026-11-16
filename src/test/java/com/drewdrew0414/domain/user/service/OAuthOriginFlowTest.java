package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.OAuthOriginMismatchException;
import com.drewdrew0414.domain.user.oauth.OAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 쿠키 발급 전 canonical 호스트로 이동하는 서비스 경계를 검사합니다.
 * 모형 요청/응답과 저장소 가짜 객체만 사용하며 외부 OAuth·실 DB에는 접근하지 않습니다.
 */
@ExtendWith(MockitoExtension.class)
class OAuthOriginFlowTest {
    @Mock OAuthClient client;
    @Mock UserRepository users;
    @Mock PasswordEncoder passwords;
    @Mock AuthService auth;
    private OAuthService service;

    @BeforeEach void setup() {
        when(client.getProvider()).thenReturn(Provider.GOOGLE);
        service = new OAuthService(List.of(client), users, passwords, auth);
        service.init();
    }

    @Test
    void metadataAlwaysSelectsTheConfiguredHttpsOriginWithoutConstructingAProviderRequest() {
        when(client.getRedirectUri()).thenReturn("https://localhost:8443/api/auth/oauth/google/callback");
        var current = request("localhost");
        current.setScheme("http"); current.setSecure(false); current.setServerPort(8080);
        assertEquals("https://localhost:8443/api/auth/oauth/google/start", service.loginStartUrl(Provider.GOOGLE, current));
        verify(client, never()).buildAuthorizeUrl(anyString());
        verify(client, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, passwords, auth);
    }

    @Test
    void aliasRequestDoesNotIssueACookieOrConstructAProviderRequest() {
        when(client.getRedirectUri()).thenReturn("https://localhost:8443/api/auth/oauth/google/callback");
        var response = new MockHttpServletResponse();
        String destination = service.buildAuthorizeUrl(Provider.GOOGLE, request("127.0.0.1"), response);
        assertEquals("https://localhost:8443/api/auth/oauth/google/start", destination);
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verify(client, never()).buildAuthorizeUrl(anyString());
        verify(client, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, passwords, auth);
    }

    @Test
    void canonicalNavigationIssuesExactlyTheStateSentToTheProviderWithSecureCookieFlags() {
        when(client.getRedirectUri()).thenReturn("https://localhost:8443/api/auth/oauth/google/callback");
        when(client.buildAuthorizeUrl(anyString())).thenAnswer(invocation ->
                "https://provider.example.invalid/authorize?state=" + invocation.getArgument(0, String.class));
        var response = new MockHttpServletResponse();
        String destination = service.buildAuthorizeUrl(Provider.GOOGLE, request("localhost"), response);
        String state = destination.substring(destination.indexOf("state=") + 6);
        assertFalse(state.isBlank());
        String cookie = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("oauthState=" + state + ";")).findFirst().orElseThrow();
        assertTrue(cookie.contains("Path=/api/auth/oauth"));
        assertTrue(cookie.contains("Max-Age=300"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertFalse(cookie.toLowerCase(java.util.Locale.ROOT).contains("domain="));
        verify(client).buildAuthorizeUrl(state);
        verify(client, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, passwords, auth);
    }

    @Test
    void aLanBrowserWithALoopbackCallbackGetsAnActionableErrorWithoutStartingAFlow() {
        when(client.getRedirectUri()).thenReturn("https://localhost:8443/api/auth/oauth/google/callback");
        var response = new MockHttpServletResponse();
        var failure = assertThrows(OAuthOriginMismatchException.class,
                () -> service.buildAuthorizeUrl(Provider.GOOGLE, request("192.0.2.8"), response));
        assertEquals(409, failure.getErrorCode().getStatus().value());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verify(client, never()).buildAuthorizeUrl(anyString());
        verify(client, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, passwords, auth);
    }

    @Test
    void aConfiguredPublicCallbackAllowsSafeTopLevelNavigationFromALanAlias() {
        when(client.getRedirectUri()).thenReturn("https://login.example.invalid/api/auth/oauth/google/callback");
        var request = request("192.0.2.8");
        request.addHeader("X-Forwarded-Host", "attacker.invalid");
        request.addHeader("Forwarded", "host=attacker.invalid;proto=https");
        var response = new MockHttpServletResponse();
        assertEquals("https://login.example.invalid/api/auth/oauth/google/start",
                service.buildAuthorizeUrl(Provider.GOOGLE, request, response));
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verify(client, never()).buildAuthorizeUrl(anyString());
        verify(client, never()).fetchUserInfo(anyString(), anyString());
    }

    @Test
    void missingAndMismatchedStateRemainRejectedBeforeProviderOrAccountAccess() {
        for (String[] states : new String[][]{{null, null}, {"nonce", null}, {null, "nonce"},
                {"nonce", "different"}, {"", ""}, {" ", " "}}) {
            var response = new MockHttpServletResponse();
            assertThrows(InvalidOAuthStateException.class, () -> service.loginOrSignup(
                    Provider.GOOGLE, "fake-code", states[0], states[1], response));
            assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        }
        verify(client, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, passwords, auth);
    }

    @Test
    void aMatchingStateStillUsesTheExistingProviderAndTokenPipelineThenExpiresItsCookie() {
        String state = "trusted-browser-state";
        User existing = User.builder().username("oauth-fixture").email("fixture@example.invalid").password("unused").build();
        when(client.fetchUserInfo("fake-code", state)).thenReturn(
                new OAuthUserInfo(Provider.GOOGLE, "fixture-provider-id", "fixture@example.invalid", "fixture"));
        when(users.findLockedByProviderAndProviderId(Provider.GOOGLE, "fixture-provider-id")).thenReturn(Optional.of(existing));
        var response = new MockHttpServletResponse();
        var tokens = TokenResponse.builder().accessToken("fake-token-only").build();
        when(auth.issueTokens(existing, response)).thenReturn(tokens);
        assertSame(tokens, service.loginOrSignup(Provider.GOOGLE, "fake-code", state, state, response));
        assertTrue(response.getHeaders("Set-Cookie").stream().anyMatch(cookie ->
                cookie.startsWith("oauthState=") && cookie.contains("Max-Age=0")
                        && cookie.contains("Secure") && cookie.contains("HttpOnly") && cookie.contains("SameSite=Lax")));
        verify(client).fetchUserInfo("fake-code", state);
        verify(auth).issueTokens(existing, response);
        verify(users, never()).save(any());
        verifyNoInteractions(passwords);
    }

    private static MockHttpServletRequest request(String host) {
        var request = new MockHttpServletRequest("GET", "/api/auth/oauth/google/authorize-url");
        request.setScheme("https"); request.setSecure(true);
        request.setServerName(host); request.setServerPort(8443);
        return request;
    }
}
