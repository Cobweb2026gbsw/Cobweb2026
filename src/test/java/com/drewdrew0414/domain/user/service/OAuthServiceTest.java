package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.UserStatus;
import com.drewdrew0414.domain.user.exception.BannedUserException;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.OAuthEmailNotFoundException;
import com.drewdrew0414.domain.user.exception.OAuthSignupUnavailableException;
import com.drewdrew0414.domain.user.exception.UnsupportedOAuthProviderException;
import com.drewdrew0414.global.exception.ApiException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
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
        when(userRepository.findLockedByProviderAndProviderId(Provider.GOOGLE, "provider-id"))
                .thenReturn(Optional.empty());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(OAuthEmailNotFoundException.class,
                () -> oAuthService.loginOrSignup(
                        Provider.GOOGLE, "code", "same-state", "same-state", response));

        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loginOrSignup_doesNotIssueTokensForStoppedAccount() {
        User existing = User.builder().username("stopped-user").email("user@example.com").password("unused").build();
        ReflectionTestUtils.setField(existing, "status", UserStatus.STOPPED);
        when(oAuthClient.fetchUserInfo("code", "same-state"))
                .thenReturn(new OAuthUserInfo(Provider.GOOGLE, "provider-id", "user@example.com", "name"));
        when(userRepository.findLockedByProviderAndProviderId(Provider.GOOGLE, "provider-id"))
                .thenReturn(Optional.of(existing));

        assertThrows(BannedUserException.class, () -> oAuthService.loginOrSignup(
                Provider.GOOGLE, "code", "same-state", "same-state", new MockHttpServletResponse()));
        verify(authService, never()).issueTokens(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void naverNewSignupIsBlockedWithoutVerifiedEmailEvidence() {
        when(oAuthClient.getProvider()).thenReturn(Provider.NAVER);
        oAuthService.init();
        when(oAuthClient.fetchUserInfo("code", "same-state"))
                .thenReturn(new OAuthUserInfo(Provider.NAVER, "provider-id", "unverified@example.com", "name"));
        when(userRepository.findLockedByProviderAndProviderId(Provider.NAVER, "provider-id"))
                .thenReturn(Optional.empty());

        assertThrows(OAuthSignupUnavailableException.class, () -> oAuthService.loginOrSignup(
                Provider.NAVER, "code", "same-state", "same-state", new MockHttpServletResponse()));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void existingNaverAccountCanStillLogIn() {
        when(oAuthClient.getProvider()).thenReturn(Provider.NAVER);
        oAuthService.init();
        User existing = User.builder().username("naver-user").email("old@example.com").password("unused").build();
        when(oAuthClient.fetchUserInfo("code", "same-state"))
                .thenReturn(new OAuthUserInfo(Provider.NAVER, "provider-id", null, "name"));
        when(userRepository.findLockedByProviderAndProviderId(Provider.NAVER, "provider-id"))
                .thenReturn(Optional.of(existing));

        oAuthService.loginOrSignup(Provider.NAVER, "code", "same-state", "same-state", new MockHttpServletResponse());
        verify(authService).issueTokens(org.mockito.ArgumentMatchers.eq(existing), org.mockito.ArgumentMatchers.any());
    }

    @Test void invalidProviderIdentitiesNeverReachAccountLookupOrTokenIssuance() {
        for(OAuthUserInfo info:java.util.Arrays.asList(null,
                new OAuthUserInfo(Provider.GOOGLE,null,"user@example.invalid","name"),
                new OAuthUserInfo(Provider.GOOGLE," ","user@example.invalid","name"),
                new OAuthUserInfo(Provider.GOOGLE,"x".repeat(256),"user@example.invalid","name"),
                new OAuthUserInfo(Provider.GITHUB,"wrong-provider","user@example.invalid","name"))) {
            when(oAuthClient.fetchUserInfo("code","same-state")).thenReturn(info);
            var error=assertThrows(ApiException.class,()->oAuthService.loginOrSignup(
                Provider.GOOGLE,"code","same-state","same-state",new MockHttpServletResponse()));
            assertEquals(502,error.getStatusCode().value());
        }
        verifyNoInteractions(userRepository,authService,passwordEncoder);
    }

    @Test void matchingBlankStatesDoNotAuthorizeAnOAuthCallback() {
        assertThrows(InvalidOAuthStateException.class,()->oAuthService.loginOrSignup(
            Provider.GOOGLE,"code"," "," ",new MockHttpServletResponse()));
        verify(oAuthClient,never()).fetchUserInfo(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(userRepository,authService);
    }

    @Test void blankAuthorizationCodeDoesNotCallTheProvider() {
        var error=assertThrows(ApiException.class,()->oAuthService.loginOrSignup(
            Provider.GOOGLE," ","same-state","same-state",new MockHttpServletResponse()));
        assertEquals(400,error.getStatusCode().value());
        verify(oAuthClient,never()).fetchUserInfo(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
    }

    @Test void anUnavailableProviderIsRejectedWithoutSettingAnOAuthCookie() {
        var response=new MockHttpServletResponse();
        assertThrows(UnsupportedOAuthProviderException.class,()->oAuthService.buildAuthorizeUrl(Provider.NAVER,response));
        assertTrue(response.getHeaders("Set-Cookie").isEmpty());
    }
}
