package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.OAuthOriginMismatchException;
import com.drewdrew0414.global.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 신뢰된 서버 콜백 설정과 브라우저 쿠키 호스트의 관계를 검사합니다.
 * 실제 DNS·TLS·브라우저·OAuth 제공자 인증이 성공했다는 증거는 아닙니다.
 */
class OAuthRedirectPolicyTest {
    private static final String HOST = "login.example.invalid";

    @ParameterizedTest
    @EnumSource(Provider.class)
    void theConfiguredCanonicalHostNeedsNoAdditionalNavigation(Provider provider) {
        assertNull(OAuthRedirectPolicy.startRedirect(provider, callback(provider, HOST), request(HOST)));
    }

    @Test
    void localhostAndLoopbackIpAreDifferentCookieHostsAndUseCanonicalTopLevelNavigation() {
        assertEquals("https://localhost:8443/api/auth/oauth/google/start",
                OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                        "https://localhost:8443/api/auth/oauth/google/callback", request("127.0.0.1")));
        assertEquals("https://127.0.0.1:8443/api/auth/oauth/google/start",
                OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                        "https://127.0.0.1:8443/api/auth/oauth/google/callback", request("localhost")));
    }

    @Test
    void aLanAliasUsesOnlyTheExplicitlyConfiguredPublicHost() {
        assertEquals("https://login.example.invalid:8443/api/auth/oauth/github/start",
                OAuthRedirectPolicy.startRedirect(Provider.GITHUB, callback(Provider.GITHUB, HOST), request("192.0.2.8")));
    }

    @Test
    void lanDevicesAreNotRedirectedToTheirOwnLocalhost() {
        for (String target : new String[]{"localhost", "127.0.0.1", "[::1]"}) {
            var failure = assertThrows(OAuthOriginMismatchException.class,
                    () -> OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                            callback(Provider.GOOGLE, target), request("192.0.2.8")));
            assertEquals(409, failure.getErrorCode().getStatus().value());
        }
    }

    @Test
    void hostComparisonIsCaseInsensitiveButDoesNotConfuseLookalikeDomains() {
        assertNull(OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                callback(Provider.GOOGLE, "LOGIN.EXAMPLE.INVALID"), request(HOST)));
        assertEquals("https://login.example.invalid:8443/api/auth/oauth/google/start",
                OAuthRedirectPolicy.startRedirect(Provider.GOOGLE, callback(Provider.GOOGLE, HOST),
                        request("login.example.invalid.attacker.invalid")));
    }

    @Test
    void reverseProxySchemeAndInternalPortDoNotOverrideCookieHostPolicy() {
        var request = request(HOST);
        request.setScheme("http");
        request.setSecure(false);
        request.setServerPort(8080);
        assertNull(OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                "https://login.example.invalid/api/auth/oauth/google/callback", request));
    }

    @Test
    void forwardingHeadersCannotChooseTheTrustedCallbackOrStartAddress() {
        var request = request("192.0.2.8");
        request.addHeader("Host", "attacker.invalid:443");
        request.addHeader("Forwarded", "host=attacker.invalid;proto=https");
        request.addHeader("X-Forwarded-Host", "attacker.invalid");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Port", "443");
        String destination = OAuthRedirectPolicy.startRedirect(Provider.GOOGLE, callback(Provider.GOOGLE, HOST), request);
        assertEquals("https://login.example.invalid:8443/api/auth/oauth/google/start", destination);
        assertFalse(destination.contains("attacker"));
    }

    @Test
    void loopbackHttpRemainsAnExplicitDevelopmentOnlyConfiguration() {
        assertEquals("http://localhost:8080/api/auth/oauth/google/start",
                OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                        "http://localhost:8080/api/auth/oauth/google/callback", request("127.0.0.1")));
        assertNull(OAuthRedirectPolicy.startRedirect(Provider.GOOGLE,
                "https://[::1]:8443/api/auth/oauth/google/callback", request("::1")));
    }

    @ParameterizedTest
    @MethodSource("invalidCallbacks")
    void invalidConfigurationFailsClosedWithoutReflectingItsRawValue(String configured) {
        ApiException failure = assertThrows(ApiException.class,
                () -> OAuthRedirectPolicy.startRedirect(Provider.GOOGLE, configured, request(HOST)));
        assertEquals(500, failure.getStatusCode().value());
        assertFalse(failure.getMessage().contains("secret-sentinel"));
        if (configured != null && !configured.isBlank()) assertFalse(failure.getMessage().contains(configured));
    }

    static Stream<String> invalidCallbacks() {
        return Stream.of(null, "", " ", "/api/auth/oauth/google/callback",
                "javascript:secret-sentinel", "//attacker.invalid/api/auth/oauth/google/callback",
                "http://login.example.invalid/api/auth/oauth/google/callback",
                "https://user:secret-sentinel@login.example.invalid/api/auth/oauth/google/callback",
                "https://login.example.invalid/api/auth/oauth/google/callback?code=secret-sentinel",
                "https://login.example.invalid/api/auth/oauth/google/callback#secret-sentinel",
                "https://login.example.invalid:0/api/auth/oauth/google/callback",
                "https://login.example.invalid:65536/api/auth/oauth/google/callback",
                "https://0.0.0.0:8443/api/auth/oauth/google/callback",
                "https://[::]:8443/api/auth/oauth/google/callback",
                "https://login.example.invalid/api/auth/oauth/github/callback",
                "https://login.example.invalid/not-the-callback",
                "https://login.example.invalid\\attacker.invalid/api/auth/oauth/google/callback",
                "https://login.example.invalid/api/auth/oauth/google/callback\nsecret-sentinel");
    }

    private static String callback(Provider provider, String host) {
        return "https://" + host + ":8443/api/auth/oauth/" + provider.name().toLowerCase(Locale.ROOT) + "/callback";
    }

    private static MockHttpServletRequest request(String host) {
        var request = new MockHttpServletRequest("GET", "/api/auth/oauth/google/authorize-url");
        request.setScheme("https");
        request.setSecure(true);
        request.setServerName(host);
        request.setServerPort(8443);
        return request;
    }
}
