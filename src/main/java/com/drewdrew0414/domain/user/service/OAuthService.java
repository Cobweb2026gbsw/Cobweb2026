package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.exception.InvalidOAuthStateException;
import com.drewdrew0414.domain.user.exception.BannedUserException;
import com.drewdrew0414.domain.user.exception.OAuthEmailConflictException;
import com.drewdrew0414.domain.user.exception.OAuthEmailNotFoundException;
import com.drewdrew0414.domain.user.exception.OAuthSignupUnavailableException;
import com.drewdrew0414.domain.user.exception.UnsupportedOAuthProviderException;
import com.drewdrew0414.domain.user.oauth.OAuthClient;
import com.drewdrew0414.domain.user.oauth.OAuthUserInfo;
import com.drewdrew0414.domain.user.oauth.OAuthResponseValidation;
import com.drewdrew0414.domain.user.oauth.OAuthRedirectPolicy;
import com.drewdrew0414.global.exception.ApiException;
import com.drewdrew0414.domain.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private static final String USER_EMAIL_UNIQUE_INDEX = "uk_users_email";

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

    /** fetch에서 state를 만들지 않습니다. 설정된 HTTPS origin으로 먼저 최상위 이동합니다. */
    public String loginStartUrl(Provider provider, HttpServletRequest request) {
        return OAuthRedirectPolicy.canonicalStartUri(provider, resolveClient(provider).getRedirectUri(), request);
    }

    public String buildAuthorizeUrl(Provider provider, HttpServletRequest request, HttpServletResponse response) {
        OAuthClient client=resolveClient(provider);
        String start = OAuthRedirectPolicy.startRedirect(provider, client.getRedirectUri(), request);
        // fetch에 교차 호스트 302를 보내면 CORS/쿠키 문제로 이어집니다. 클라이언트가
        // 이 주소로 최상위 이동한 후 /start가 canonical host에서 state를 발급합니다.
        if (start != null) return start;
        String state = UUID.randomUUID().toString();
        // CSRF 방지용 임의값을 매 요청마다 새로 만들고, 브라우저만 다시 보낼 수 있는 HttpOnly 쿠키에도 저장합니다.
        // 제공자가 callback으로 돌려주는 state와 이 쿠키를 비교해야 state가 실제 방어 수단으로 동작합니다.
        setOAuthStateCookie(response, state);
        return client.buildAuthorizeUrl(state);
    }

    @Transactional
    // 사용자 조회/생성과 토큰 발급이 하나의 흐름으로 처리돼야 함
    public TokenResponse loginOrSignup(Provider provider, String code, String state, String savedState,
                                       HttpServletResponse response) {
        validateState(state, savedState);
        if(code==null || code.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST,"소셜 로그인 인가 코드가 필요합니다.");
        clearOAuthStateCookie(response);
        // 검증이 끝난 state 쿠키는 즉시 만료시켜 한 번 시작한 OAuth 흐름에서만 사용되도록 함

        OAuthUserInfo info = OAuthResponseValidation.requireIdentity(provider,resolveClient(provider).fetchUserInfo(code, state));

        User user = userRepository.findLockedByProviderAndProviderId(provider, info.providerId())
                .orElseGet(() -> registerOAuthUser(provider, info));

        if (!user.isLoginable()) throw new BannedUserException();

        return authService.issueTokens(user, response);
        // 로컬 로그인과 완전히 같은 방식(access/refresh token 발급 + 쿠키 세팅)으로 로그인 처리
    }

    private User registerOAuthUser(Provider provider, OAuthUserInfo info) {
        if (provider == Provider.NAVER) {
            // 이메일 검증 여부를 제공하지 않는 네이버는 기존 provider ID의 로그인만 허용합니다.
            throw new OAuthSignupUnavailableException();
        }
        if (info.email() == null || info.email().isBlank()) {
            throw new OAuthEmailNotFoundException();
        }
        if (userRepository.existsByEmail(info.email())) {
            // 이메일을 복구 주소로 쓸 수 있어도 OAuth 식별자를 기존 계정에 자동 연결하지 않습니다.
            throw new OAuthEmailConflictException();
        }

        User newUser = createUser(provider, info);
        try {
            // 검사와 생성 사이에 다른 가입이 완료되어도 유일 제약 위반을 이 로그인 흐름에서 처리합니다.
            return userRepository.saveAndFlush(newUser);
        } catch (DataIntegrityViolationException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && "23505".equals(violation.getSQLState())
                        && USER_EMAIL_UNIQUE_INDEX.equals(violation.getConstraintName())) {
                    throw new OAuthEmailConflictException();
                }
            }
            throw error;
        }
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
        OAuthClient client=provider==null || clientsByProvider==null?null:clientsByProvider.get(provider);
        if(client==null) throw new UnsupportedOAuthProviderException();
        return client;
    }

    private void validateState(String returnedState, String savedState) {
        if (returnedState == null || savedState == null || returnedState.isBlank() || savedState.isBlank()) {
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
