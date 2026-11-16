package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.OAuthOriginMismatchException;
import com.drewdrew0414.domain.user.service.OAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 신뢰 호스트로의 최상위 OAuth 이동과 캐시 방지 계약만 검사하며 네트워크는 사용하지 않습니다. */
@ExtendWith(MockitoExtension.class)
class OAuthStartControllerTest {
    @Mock OAuthService service;
    private OAuthController controller;

    @BeforeEach void setup() { controller = new OAuthController(service); }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void topLevelStartNavigatesToTheServiceApprovedProviderDestination(Provider provider) throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        String destination = "https://provider.example.invalid/authorize?state=fake-test-state";
        when(service.buildAuthorizeUrl(provider, request, response)).thenReturn(destination);
        controller.start(provider.name().toLowerCase(Locale.ROOT), request, response);
        assertEquals(302, response.getStatus());
        assertEquals(destination, response.getRedirectedUrl());
        assertPrivateNavigationHeaders(response);
        verify(service).buildAuthorizeUrl(provider, request, response);
        verifyNoMoreInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void aDifferentCookieHostGetsOnlyACanonicalStartNavigation(Provider provider) throws Exception {
        var request = new MockHttpServletRequest();
        request.setServerName("127.0.0.1");
        request.addHeader("X-Forwarded-Host", "attacker.invalid");
        var response = new MockHttpServletResponse();
        String canonical = "https://localhost:8443/api/auth/oauth/" + provider.name().toLowerCase(Locale.ROOT) + "/start";
        when(service.buildAuthorizeUrl(provider, request, response)).thenReturn(canonical);
        controller.start(provider.name().toLowerCase(Locale.ROOT), request, response);
        assertEquals(canonical, response.getRedirectedUrl());
        assertFalse(response.getRedirectedUrl().contains("attacker.invalid"));
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        assertPrivateNavigationHeaders(response);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void jsonAuthorizeResponsesRemainUncachedAndDoNotPerformAFetchRedirect(Provider provider) {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        String canonical = "https://localhost:8443/api/auth/oauth/" + provider.name().toLowerCase(Locale.ROOT) + "/start";
        when(service.loginStartUrl(provider, request)).thenReturn(canonical);
        var result = controller.authorizeUrl(provider.name().toLowerCase(Locale.ROOT), request, response);
        assertTrue(result.isSuccess());
        assertEquals(canonical, result.getData().getAuthorizeUrl());
        assertNull(response.getRedirectedUrl());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
        verify(service, never()).buildAuthorizeUrl(any(), any(), any());
        assertPrivateNavigationHeaders(response);
    }

    @Test
    void rejectedOriginNeverRedirectsOrIssuesCookies() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        when(service.buildAuthorizeUrl(Provider.GOOGLE, request, response)).thenThrow(new OAuthOriginMismatchException());
        assertThrows(OAuthOriginMismatchException.class, () -> controller.start("google", request, response));
        assertNull(response.getRedirectedUrl());
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }

    private static void assertPrivateNavigationHeaders(MockHttpServletResponse response) {
        assertNotNull(response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
    }
}
