package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.UnsupportedOAuthProviderException;
import com.drewdrew0414.domain.user.service.OAuthService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 외부 제공자·네트워크·Spring 컨텍스트 없이 콜백의 React 복귀 계약만 검사합니다.
 * 실제 OAuth 인증, 제공자 설정, TLS와 쿠키 전달 성공을 입증하는 테스트는 아닙니다.
 */
@ExtendWith(MockitoExtension.class)
class OAuthControllerTest {
    @Mock private OAuthService service;
    @Mock private HttpServletResponse response;
    private OAuthController controller;

    @BeforeEach
    void setUp() {
        controller = new OAuthController(service);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void callbackPassesProviderCodeAndBothStatesThenRedirectsOnlyThroughFragment(Provider provider) throws Exception {
        String token = "token.part+/= &한글#other=value";
        when(service.loginOrSignup(provider, "provider-code", "returned-state", "cookie-state", response))
                .thenReturn(TokenResponse.builder().accessToken(token).build());

        controller.callback(provider.name().toLowerCase(Locale.ROOT), "provider-code",
                "returned-state", "cookie-state", response);

        verify(service).loginOrSignup(provider, "provider-code", "returned-state", "cookie-state", response);
        ArgumentCaptor<String> redirect = ArgumentCaptor.forClass(String.class);
        verify(response).sendRedirect(redirect.capture());
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        assertEquals("/oauth/callback#accessToken=" + encoded, redirect.getValue());
        URI destination = URI.create(redirect.getValue());
        assertEquals("/oauth/callback", destination.getPath());
        assertNull(destination.getRawQuery());
        assertEquals(token, URLDecoder.decode(destination.getRawFragment().substring("accessToken=".length()),
                StandardCharsets.UTF_8));
        verifyNoMoreInteractions(service, response);
    }

    @ParameterizedTest
    @EnumSource(Provider.class)
    void authorizeUrlPassesProviderAndResponseWithoutRedirecting(Provider provider) {
        String url = "https://provider.example/authorize?state=server-generated";
        when(service.buildAuthorizeUrl(provider, response)).thenReturn(url);

        var result = controller.authorizeUrl(provider.name().toLowerCase(Locale.ROOT), response);

        assertTrue(result.isSuccess());
        assertEquals(url, result.getData().getAuthorizeUrl());
        verify(service).buildAuthorizeUrl(provider, response);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(response);
    }

    @Test
    void stateFailureDoesNotRedirectOrExposeToken() {
        when(service.loginOrSignup(Provider.GOOGLE, "code", null, null, response))
                .thenThrow(new InvalidOAuthStateException());

        assertThrows(InvalidOAuthStateException.class,
                () -> controller.callback("google", "code", null, null, response));

        verify(service).loginOrSignup(Provider.GOOGLE, "code", null, null, response);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(response);
    }

    @Test
    void unsupportedProviderDoesNotCallServiceOrRedirect() {
        assertThrows(UnsupportedOAuthProviderException.class,
                () -> controller.callback("unknown", "code", "state", "cookie", response));
        assertThrows(UnsupportedOAuthProviderException.class,
                () -> controller.authorizeUrl("unknown", response));

        verifyNoInteractions(service, response);
    }
}
