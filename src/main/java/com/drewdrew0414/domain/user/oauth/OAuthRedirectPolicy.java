package com.drewdrew0414.domain.user.oauth;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.exception.OAuthOriginMismatchException;
import com.drewdrew0414.global.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * state 쿠키를 제공자가 돌아올 호스트에서만 발급합니다.
 * localhost와 127.0.0.1은 같은 컴퓨터라도 서로 다른 쿠키 호스트입니다.
 * 잘못된 호스트에서는 서버에 명시된 콜백 origin의 /start로 최상위 이동한 뒤
 * 쿠키를 만들며, 요청 Host나 Forwarded 값을 새 redirect_uri로 신뢰하지 않습니다.
 */
public final class OAuthRedirectPolicy {
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    private OAuthRedirectPolicy() {}

    /** null이면 현재 호스트에서 발급 가능, URL이면 그 주소로 먼저 이동해야 합니다. */
    public static String startRedirect(Provider provider, String configuredRedirectUri,
                                       HttpServletRequest request) {
        String start = canonicalStartUri(provider, configuredRedirectUri, request);
        URI callback = callbackUri(provider, configuredRedirectUri);
        return host(callback.getHost()).equals(host(request.getServerName())) ? null : start;
    }

    /** JSON fetch는 항상 이 주소만 반환합니다. 쿠키는 최상위 /start 방문에서 발급합니다. */
    public static String canonicalStartUri(Provider provider, String configuredRedirectUri,
                                           HttpServletRequest request) {
        if (provider == null || request == null) throw invalidConfiguration();
        URI callback = callbackUri(provider, configuredRedirectUri);
        String canonicalHost = host(callback.getHost());
        String incomingHost = host(request.getServerName());
        // 다른 기기의 localhost는 서버가 아니라 그 기기 자신입니다. 여기에 자동 이동하면
        // 로그인 실패만 뒤로 미뤄지므로, 공개 주소 설정 오류를 일찍 안내합니다.
        if (!canonicalHost.equals(incomingHost) && LOOPBACK.contains(canonicalHost) && !LOOPBACK.contains(incomingHost)) {
            throw new OAuthOriginMismatchException();
        }
        return callback.resolve("start").toASCIIString();
    }

    private static URI callbackUri(Provider provider, String value) {
        try {
            if (value == null || value.isEmpty() || value.chars().anyMatch(c -> c <= 32 || c == 127 || c == '\\'))
                throw invalidConfiguration();
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String hostname = host(uri.getHost());
            String path = "/api/auth/oauth/" + provider.name().toLowerCase(Locale.ROOT) + "/callback";
            if (hostname.isEmpty() || Set.of("0.0.0.0", "::").contains(hostname) ||
                    !("https".equals(scheme) || "http".equals(scheme) && LOOPBACK.contains(hostname)) ||
                    uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null ||
                    uri.getRawQuery() != null || uri.getRawFragment() != null || !path.equals(uri.getRawPath()))
                throw invalidConfiguration();
            return uri;
        } catch (IllegalArgumentException error) {
            // URI 파서의 오류에는 원문(잘못 복사한 비밀값 포함)이 들어갈 수 있어 전달하지 않습니다.
            throw invalidConfiguration();
        }
    }

    private static String host(String value) {
        if (value == null) return "";
        String result = value.toLowerCase(Locale.ROOT);
        return result.startsWith("[") && result.endsWith("]") ? result.substring(1, result.length() - 1) : result;
    }

    private static ApiException invalidConfiguration() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "소셜 로그인 콜백 주소 설정을 확인해주세요.");
    }
}
