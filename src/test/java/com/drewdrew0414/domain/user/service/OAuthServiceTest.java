package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.OAuthEmailNotFoundException;
import com.drewdrew0414.domain.user.oauth.OAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 소셜 로그인에서 가장 중요한 state 검증과 필수 이메일 확인을 테스트합니다.
 * 실제 Google/Naver/GitHub로 네트워크 요청을 보내지 않고 OAuthClient를 가짜로 두어,
 * 우리 서버가 외부 API를 호출하기 전에 수행해야 할 검증 순서에 집중합니다.
 */
@ExtendWith(MockitoExtension.class)
class OAuthServiceTest {
    @Mock
    private OAuthClient oAuthClient;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuthService authService;

    private OAuthService oAuthService;

    @BeforeEach
    void setUp() {
        when(oAuthClient.getProvider()).thenReturn(Provider.GOOGLE);
        oAuthService = new OAuthService(List.of(oAuthClient), userRepository, passwordEncoder, authService);
        oAuthService.init();
    }

    @Test
    void buildAuthorizeUrl_storesSameStateInHttpOnlyCookie() {
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);
        when(oAuthClient.buildAuthorizeUrl(stateCaptor.capture())).thenReturn("https://provider.example/authorize");
        MockHttpServletResponse response = new MockHttpServletResponse();

        oAuthService.buildAuthorizeUrl(Provider.GOOGLE, response);

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("oauthState=" + stateCaptor.getValue()));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
    }

    @Test
    void loginOrSignup_rejectsMismatchedStateBeforeCallingProvider() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(InvalidOAuthStateException.class,
                () -> oAuthService.loginOrSignup(
                        Provider.GOOGLE, "code", "returned-state", "saved-state", response));

        verify(oAuthClient, never()).fetchUserInfo("code", "returned-state");
    }

    @Test
    void loginOrSignup_rejectsAccountWithoutVerifiedEmail() {
        when(oAuthClient.fetchUserInfo("code", "same-state"))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, "provider-id", null, "name"));
        when(userRepository.findByProviderAndProviderId(Provider.GOOGLE, "provider-id"))
                .thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(OAuthEmailNotFoundException.class,
                () -> oAuthService.loginOrSignup(
                        Provider.GOOGLE, "code", "same-state", "same-state", response));

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
