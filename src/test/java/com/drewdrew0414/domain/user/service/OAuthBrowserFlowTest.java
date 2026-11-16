package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.controller.OAuthController;
import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.oauth.OAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.repository.UserRepository;
import com.drewdrew0414.global.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** MVC 매핑·쿠키 수신·state 비교를 연결한 모형 회귀입니다. 실제 Google/DB/브라우저는 사용하지 않습니다. */
class OAuthBrowserFlowTest {
    private OAuthClient provider;
    private UserRepository users;
    private AuthService auth;
    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() {
        provider = mock(OAuthClient.class);
        users = mock(UserRepository.class);
        auth = mock(AuthService.class);
        when(provider.getProvider()).thenReturn(Provider.GOOGLE);
        when(provider.getRedirectUri()).thenReturn("https://localhost:8443/api/auth/oauth/google/callback");
        when(provider.buildAuthorizeUrl(anyString())).thenAnswer(call ->
                "https://provider.example.invalid/authorize?state=" + call.getArgument(0, String.class));
        var service = new OAuthService(List.of(provider), users, mock(PasswordEncoder.class), auth);
        ReflectionTestUtils.invokeMethod(service, "init");
        mvc = MockMvcBuilders.standaloneSetup(new OAuthController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void aliasToCanonicalStartThenCallbackCompletesWithoutCrossHostCookieIssuance() throws Exception {
        var metadata = mvc.perform(onHost(get("/api/auth/oauth/google/authorize-url"), "127.0.0.1"))
                .andReturn().getResponse();
        assertEquals(200, metadata.getStatus());
        assertTrue(metadata.getHeaders("Set-Cookie").isEmpty());
        assertEquals("https://localhost:8443/api/auth/oauth/google/start",
                json.readTree(metadata.getContentAsString()).get("data").get("authorizeUrl").stringValue());
        verify(provider, never()).buildAuthorizeUrl(anyString());

        var started = mvc.perform(onHost(get("/api/auth/oauth/google/start"), "localhost"))
                .andReturn().getResponse();
        assertEquals(302, started.getStatus());
        String state = started.getHeader("Set-Cookie").split(";", 2)[0].substring("oauthState=".length());
        assertEquals("https://provider.example.invalid/authorize?state=" + state, started.getRedirectedUrl());
        assertTrue(started.getHeader("Set-Cookie").contains("Secure"));
        assertEquals("no-referrer", started.getHeader("Referrer-Policy"));

        User user = User.builder().username("oauth-model").email("model@example.invalid").password("unused").build();
        when(provider.fetchUserInfo("fake-code", state)).thenReturn(new OAuthUserInfo(Provider.GOOGLE, "fake-id", "model@example.invalid", "model"));
        when(users.findLockedByProviderAndProviderId(Provider.GOOGLE, "fake-id")).thenReturn(Optional.of(user));
        when(auth.issueTokens(eq(user), any())).thenReturn(TokenResponse.builder().accessToken("fake-token").build());
        var callback = mvc.perform(onHost(get("/api/auth/oauth/google/callback")
                        .param("code", "fake-code").param("state", state)
                        .cookie(new Cookie("oauthState", state)), "localhost"))
                .andReturn().getResponse();
        assertEquals(302, callback.getStatus());
        assertEquals("/oauth/callback#accessToken=fake-token", callback.getRedirectedUrl());
        assertTrue(callback.getHeader("Set-Cookie").contains("Max-Age=0"));
        verify(provider).fetchUserInfo("fake-code", state);
        verify(users, never()).save(any());
    }

    @Test void missingCookieStillFailsAtMvcBoundaryBeforeAnyProviderOrAccountLookup() throws Exception {
        var response = mvc.perform(onHost(get("/api/auth/oauth/google/callback")
                        .param("code", "fake-code").param("state", "fake-state"), "localhost"))
                .andReturn().getResponse();
        assertEquals(401, response.getStatus());
        assertEquals("INVALID_OAUTH_STATE", json.readTree(response.getContentAsString()).get("errorCode").stringValue());
        assertNull(response.getRedirectedUrl());
        verify(provider, never()).fetchUserInfo(anyString(), anyString());
        verifyNoInteractions(users, auth);
    }

    @Test void externalDeviceNeverGetsRedirectedToItsOwnLoopbackHost() throws Exception {
        var response = mvc.perform(onHost(get("/api/auth/oauth/google/authorize-url"), "192.0.2.8"))
                .andReturn().getResponse();
        assertEquals(409, response.getStatus());
        assertEquals("OAUTH_ORIGIN_MISMATCH", json.readTree(response.getContentAsString()).get("errorCode").stringValue());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        assertNull(response.getRedirectedUrl());
        verify(provider, never()).buildAuthorizeUrl(anyString());
    }

    private static MockHttpServletRequestBuilder onHost(MockHttpServletRequestBuilder request, String host) {
        return request.with(value -> {
            value.setScheme("https"); value.setSecure(true); value.setServerName(host); value.setServerPort(8443);
            return value;
        });
    }
}
