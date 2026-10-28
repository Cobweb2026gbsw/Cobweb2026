package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.dto.request.LoginRequest;
import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.service.AuthService;
import com.drewdrew0414.domain.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LoginOptionsControllerTest {
    private MockEnvironment local() { return new MockEnvironment().withProperty("spring.profiles.active", "local"); }

    @Test void onlyAnEnabledConfiguredHttpsPortIsExposed() {
        var enabled = new LoginOptionsController(true, 18443, local()).options();
        assertTrue(enabled.isSuccess());
        assertTrue(enabled.getData().secureCookieRequired());
        assertEquals(18443, enabled.getData().httpsPort());
        assertNull(new LoginOptionsController(false, 8443, local()).options().getData().httpsPort());
        assertNull(new LoginOptionsController(true, 8443, new MockEnvironment()).options().getData().httpsPort());
        assertNull(new LoginOptionsController(true, 8443, new MockEnvironment().withProperty("spring.profiles.active", "local,prod")).options().getData().httpsPort());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 65536})
    void invalidPortsAreNotSuggested(int port) {
        assertNull(new LoginOptionsController(true, port, local()).options().getData().httpsPort());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void loginControllerForwardsTheExplicitFlagAndActualConnectionAddress(boolean remember) {
        AuthService auth = mock(AuthService.class);
        var controller = new AuthController(mock(UserService.class), auth);
        var request = new LoginRequest();
        ReflectionTestUtils.setField(request, "username", "sample-user");
        ReflectionTestUtils.setField(request, "password", "test-password");
        ReflectionTestUtils.setField(request, "rememberMe", remember);
        var http = new MockHttpServletRequest();
        http.setRemoteAddr("192.0.2.8");
        http.addHeader("X-Forwarded-For", "forged-client");
        http.addHeader("User-Agent", "test-agent");
        var response = new MockHttpServletResponse();
        when(auth.login("sample-user", "test-password", "192.0.2.8", "test-agent", remember, response))
                .thenReturn(TokenResponse.builder().accessToken("test-token").build());
        assertTrue(controller.login(request, http, response).isSuccess());
        verify(auth).login("sample-user", "test-password", "192.0.2.8", "test-agent", remember, response);
        verifyNoMoreInteractions(auth);
    }

    @Test void omittedPreferenceDefaultsToSessionCookie() {
        assertFalse(new LoginRequest().isRememberMe());
    }
}
