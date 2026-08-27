package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.response.TokenResponse;
import com.drewdrew0414.domain.user.entity.RefreshToken;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.UserLog;
import com.drewdrew0414.domain.user.exception.BannedUserException;
import com.drewdrew0414.domain.user.exception.ExpiredTokenException;
import com.drewdrew0414.domain.user.exception.InvalidTokenException;
import com.drewdrew0414.domain.user.exception.PasswordMismatchException;
import com.drewdrew0414.domain.user.exception.UserNotFoundException;
import com.drewdrew0414.domain.user.repository.RefreshTokenRepository;
import com.drewdrew0414.domain.user.repository.UserLogRepository;
import com.drewdrew0414.domain.user.repository.UserRepository;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/*
 * 로그인, 토큰 재발급, 로그아웃의 실제 비즈니스 로직을 담당하는 서비스입니다.
 * 컨트롤러는 요청/응답만 처리하고, "아이디가 존재하는지, 비밀번호가 맞는지, 계정이 정지 상태는 아닌지,
 * 토큰을 어떻게 만들지" 같은 판단은 전부 이 클래스 안에서 이루어집니다.
 * 로그인 시도마다 성공/실패 여부를 UserLog로 남기고, 발급한 refresh token은 RefreshToken으로 저장해서
 * 이후 재발급/로그아웃 요청을 처리할 수 있는 근거로 씁니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> AuthController, OAuthService가 주입받아 씀

@RequiredArgsConstructor
// final 필드들을 받는 생성자를 자동 생성 (의존성 주입용)

public class AuthService {
    private static final String REFRESH_TOKEN_COOKIE = "refreshToken";
    // 응답 쿠키/요청 쿠키에서 refresh token을 주고받을 때 쓰는 쿠키 이름, 오타 방지를 위해 상수로 고정

    private final UserRepository userRepository;
    private final UserLogRepository userLogRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    // 로그인 판정(성공/실패)과 UserLog 저장, User 필드 변경이 하나의 트랜잭션으로 묶여야 함
    public TokenResponse login(String username, String rawPassword, String clientIp, String userAgent, HttpServletResponse response) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    // 유저 자체를 못 찾은 경우 -> userId 없이 시도 기록만 남기고 예외를 던짐
                    userLogRepository.save(UserLog.failUserNotFound(username, null, clientIp, userAgent));
                    return new UserNotFoundException();
                });

        if (!user.isLoginable()) {
            // 계정 상태(ACTIVE가 아님)를 비밀번호 검증보다 먼저 확인해서, 정지된 계정은 비밀번호가 맞아도 통과 못 하게 막음
            userLogRepository.save(UserLog.bannedUser(user.getId(), username, null, clientIp, userAgent));
            throw new BannedUserException();
        }

        if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
            // matches(원문, 해시) -> BCrypt가 내부적으로 같은 salt로 다시 해시해서 비교해줌
            user.increaseLoginFailedCount();
            userLogRepository.save(UserLog.failPasswordMismatch(user.getId(), username, null, clientIp, userAgent));
            throw new PasswordMismatchException();
        }

        user.resetLoginFailedCount();
        user.recordLogin();
        userLogRepository.save(UserLog.success(user.getId(), username, null, clientIp, userAgent));

        return issueTokens(user, response);
    }

    @Transactional
    // DB에서 기존 RefreshToken을 지우고 새로 저장하는 과정이 하나의 트랜잭션으로 묶여야 함
    public TokenResponse reissue(String refreshToken, HttpServletResponse response) {
        if (refreshToken == null) {
            // 쿠키 자체가 없는 경우 (로그아웃 후 재호출 등) -> 애초에 검증할 대상이 없으니 바로 예외
            throw new InvalidTokenException();
        }
        jwtTokenProvider.validateOrThrow(refreshToken);
        // JWT 자체의 서명/만료를 먼저 검사 (위조됐거나 만료됐으면 여기서 바로 예외를 던짐)

        RefreshToken saved = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(InvalidTokenException::new);
        // DB에 없는 토큰 = 로그아웃되었거나 다른 토큰으로 이미 교체된 토큰

        if (saved.isExpired()) {
            // JWT 자체는 아직 유효해도, DB에 저장해둔 만료 시각이 더 지났다면 DB 쪽 기준을 우선함
            refreshTokenRepository.deleteByUserId(saved.getUserId());
            throw new ExpiredTokenException();
        }

        User user = userRepository.findById(saved.getUserId())
                .orElseThrow(UserNotFoundException::new);

        return issueTokens(user, response);
    }

    @Transactional
    // 토큰 삭제가 실패 없이 온전히 반영돼야 하므로 트랜잭션으로 묶음
    public void logout(Long userId, HttpServletResponse response) {
        refreshTokenRepository.deleteByUserId(userId);
        clearRefreshTokenCookie(response);
    }

    // package-private: OAuthService도 소셜 로그인 성공 시 동일한 토큰 발급 로직을 재사용합니다.
    TokenResponse issueTokens(User user, HttpServletResponse response) {
        String accessToken = jwtTokenProvider.generateAccessToken(user.getId(), user.getUsername(), user.getRole().name());
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.getId());

        refreshTokenRepository.deleteByUserId(user.getId());
        // 재로그인/재발급 시마다 기존 토큰을 지우고 새로 저장 -> 유저당 refresh token을 항상 1개만 유지 (토큰 회전)
        refreshTokenRepository.save(new RefreshToken(
                user.getId(),
                refreshToken,
                LocalDateTime.now().plus(Duration.ofMillis(jwtTokenProvider.getRefreshTokenExpiration()))
        ));

        setRefreshTokenCookie(response, refreshToken);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getAccessTokenExpiration())
                .build();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String refreshToken) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, refreshToken)
                .httpOnly(true) // JS(document.cookie)로 못 읽게 막음 -> XSS로 토큰이 털리는 걸 방지
                .secure(true)   // HTTPS(또는 localhost)에서만 전송
                .sameSite("Lax") // 다른 사이트에서 시작한 POST 요청에는 쿠키를 보내지 않아 재발급 CSRF 위험을 낮춤
                .path("/")      // 모든 경로의 요청에 이 쿠키가 실려 감
                .maxAge(Duration.ofMillis(jwtTokenProvider.getRefreshTokenExpiration()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(0) // maxAge=0 -> 브라우저에게 이 쿠키를 즉시 삭제하라고 지시하는 관용적인 방법
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
