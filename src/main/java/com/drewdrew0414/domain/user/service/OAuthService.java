package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.OAuthEmailNotFoundException;
import com.drewdrew0414.domain.user.oauth.OAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/*
 * 소셜 로그인(OAuth) 전체 흐름을 조율하는 서비스입니다.
 * 1) buildAuthorizeUrl: 프론트엔드가 이동시킬 제공자 동의 화면 URL 생성
 * 2) loginOrSignup: 콜백으로 받은 인가 코드를 이용해 사용자 정보를 조회하고,
 *    이미 가입된 (provider, providerId) 조합이면 로그인, 처음 보는 조합이면 즉시 회원가입 후 로그인시킵니다.
 * 소셜 로그인은 이메일 인증/비밀번호 절차 없이 제공자가 이미 신원을 보증해준 것으로 간주합니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> OAuthController가 주입받아 씀

@RequiredArgsConstructor
// final 필드들을 받는 생성자를 자동 생성 (의존성 주입용)

public class OAuthService {
    private static final String OAUTH_STATE_COOKIE = "oauthState";
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(5);

    private final List<OAuthClient> oAuthClients;
    // OAuthClient를 구현한 빈(Google/Naver/Github)들을 스프링이 전부 모아서 리스트로 주입해줌

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    private Map<Provider, OAuthClient> clientsByProvider;
    // Provider(enum) -> 해당 제공자의 OAuthClient 로 빠르게 찾기 위한 맵. 생성자 주입 이후 init()에서 채워짐

    @PostConstruct
    // 빈 생성 + 의존성 주입이 모두 끝난 직후 자동으로 한 번 호출됨 -> 리스트를 맵으로 미리 변환해둠
    void init() {
        clientsByProvider = oAuthClients.stream()
                .collect(Collectors.toMap(OAuthClient::getProvider, client -> client));
    }

    public String buildAuthorizeUrl(Provider provider, HttpServletResponse response) {
        String state = UUID.randomUUID().toString();
        // CSRF 방지용 임의값을 매 요청마다 새로 만들고, 브라우저만 다시 보낼 수 있는 HttpOnly 쿠키에도 저장합니다.
        // 제공자가 callback으로 돌려주는 state와 이 쿠키를 비교해야 state가 실제 방어 수단으로 동작합니다.
        setOAuthStateCookie(response, state);
        return resolveClient(provider).buildAuthorizeUrl(state);
    }

    @Transactional
    // 사용자 조회/생성과 토큰 발급이 하나의 흐름으로 처리돼야 함
    public TokenResponse loginOrSignup(Provider provider, String code, String state, String savedState,
                                       HttpServletResponse response) {
        validateState(state, savedState);
        clearOAuthStateCookie(response);
        // 검증이 끝난 state 쿠키는 즉시 만료시켜 한 번 시작한 OAuth 흐름에서만 사용되도록 함

        OAuthUserInfo info = resolveClient(provider).fetchUserInfo(code, state);

        User user = userRepository.findByProviderAndProviderId(provider, info.providerId())
                .orElseGet(() -> {
                    if (info.email() == null || info.email().isBlank()) {
                        // 새 계정은 이메일이 없으면 중복 판정과 비밀번호 복구가 불가능하므로 생성하지 않습니다.
                        // 이미 가입된 소셜 계정은 DB에 보관된 정보를 사용하므로 제공자가 이번에 이메일을 생략해도 로그인할 수 있습니다.
                        throw new OAuthEmailNotFoundException();
                    }
                    return userRepository.save(createUser(provider, info));
                });
        // 이미 가입된 (provider, providerId) 조합이면 그대로 조회, 처음 보는 조합이면 즉시 회원가입

        return authService.issueTokens(user, response);
        // 로컬 로그인과 완전히 같은 방식(access/refresh token 발급 + 쿠키 세팅)으로 로그인 처리
    }

    private User createUser(Provider provider, OAuthUserInfo info) {
        return User.builder()
                .username(generateUsername(provider, info.providerId()))
                .email(info.email())
                .provider(provider)
                .providerId(info.providerId())
                .password(passwordEncoder.encode(UUID.randomUUID().toString())) // 소셜 로그인 전용 계정, 비밀번호 로그인은 불가능
                .build();
    }

    // username은 VARCHAR(16) UNIQUE이므로, 제공자 식별자를 짧게 해시해서 만들고 충돌 시 숫자를 덧붙입니다.
    private String generateUsername(Provider provider, String providerId) {
        String prefix = switch (provider) {
            case GOOGLE -> "g";
            case NAVER -> "n";
            case GITHUB -> "gh";
        };
        String hash = Long.toString(Math.abs((long) (provider.name() + providerId).hashCode()), 36);
        // hashCode를 36진수 문자열로 바꿔서 짧게 줄임 (구글의 sub 같은 긴 숫자 ID를 그대로 못 쓰기 때문)
        String base = (prefix + hash).length() > 16 ? (prefix + hash).substring(0, 16) : prefix + hash;

        String candidate = base;
        int suffix = 1;
        while (userRepository.existsByUsername(candidate)) {
            // 해시가 우연히 겹치는 극히 드문 경우를 대비해 숫자를 붙여가며 재시도
            String suffixStr = String.valueOf(suffix++);
            candidate = base.substring(0, Math.min(base.length(), 16 - suffixStr.length())) + suffixStr;
        }
        return candidate;
    }

    private OAuthClient resolveClient(Provider provider) {
        return clientsByProvider.get(provider);
    }

    private void validateState(String returnedState, String savedState) {
        if (returnedState == null || savedState == null) {
            throw new InvalidOAuthStateException();
        }

        // 일반 equals 대신 일정한 시간에 비교하는 MessageDigest.isEqual을 사용해 미세한 응답 시간 차이로
        // state 값을 한 글자씩 추측하는 타이밍 공격의 가능성도 줄입니다.
        boolean matches = MessageDigest.isEqual(
                returnedState.getBytes(StandardCharsets.UTF_8),
                savedState.getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            throw new InvalidOAuthStateException();
        }
    }

    private void setOAuthStateCookie(HttpServletResponse response, String state) {
        ResponseCookie cookie = ResponseCookie.from(OAUTH_STATE_COOKIE, state)
                .httpOnly(true) // JavaScript가 state 값을 읽거나 바꾸지 못하게 함
                .secure(true)   // HTTPS와 localhost에서만 전송
                .sameSite("Lax") // 외부 OAuth 제공자에서 돌아오는 최상위 GET 이동에는 허용하면서 일반 교차 사이트 요청은 제한
                .path("/api/auth/oauth")
                .maxAge(OAUTH_STATE_TTL)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearOAuthStateCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(OAUTH_STATE_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/api/auth/oauth")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
