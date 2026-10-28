package com.drewdrew0414.global.security.jwt;

import com.drewdrew0414.domain.user.exception.ExpiredTokenException;
import com.drewdrew0414.domain.user.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;


/*
 * JWT를 만들고 검증하는 역할을 전담하는 클래스입니다.
 * 로그인에 성공하면 이 클래스가 access token과 refresh token을 발급하고,
 * 이후 요청이 들어올 때마다 JwtAuthenticationFilter가 이 클래스를 통해 토큰이 유효한지, 만료되지 않았는지를 검사합니다.
 * 토큰 서명에 쓰이는 비밀키와 만료 시간은 application.yaml의 jwt 설정값을 그대로 가져와 씁니다.
 */
@Component // JWT 발급/검증을 전담하는 클래스
public class JwtTokenProvider {
    private final SecretKey key;
    // AuthService가 TokenResponse.expiresIn을 채우거나 쿠키 만료시간을 계산할 때 씀
    @Getter
    private final long accessTokenExpiration;
    // AuthService가 RefreshToken 엔티티의 expiresAt이나 쿠키 maxAge를 계산할 때 씀
    @Getter
    private final long refreshTokenExpiration;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret, // application.yaml의 jwt.secret 주입
            @Value("${jwt.access-token-expiration}") long accessTokenExpiration,
            @Value("${jwt.refresh-token-expiration}") long refreshTokenExpiration
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes()); // 문자열 시크릿을 서명용 SecretKey로 변환
        this.accessTokenExpiration = accessTokenExpiration;
        this.refreshTokenExpiration = refreshTokenExpiration;
    }


    public String generateAccessToken(Long userId, String username, String role, long tokenVersion) {
        // subject(sub 클레임)에 userId를 담고, username/role은 커스텀 클레임으로 추가
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .claim("tokenVersion", tokenVersion)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key) // 위조 방지를 위해 서명 (key는 application.yaml jwt.secret 기반)
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        // 기존 OAuth/내부 호출은 종전의 14일 유지 정책을 보존합니다.
        return generateRefreshToken(userId, true);
    }

    public String generateRefreshToken(Long userId, boolean rememberMe) {
        // 권한 정보는 담지 않고 사용자 ID와 쿠키 유지 여부만 보관합니다.
        // 재발급 시 DB의 RefreshToken 레코드로 실제 유효성을 다시 확인합니다.
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
                // JWT 시간은 초 단위이므로 같은 초의 재발급도 서로 다른 토큰이어야 합니다.
                .id(java.util.UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                // 쿠키 회전 시 세션 쿠키를 영속 쿠키로 승격하지 않도록 서버가 서명해 보관합니다.
                .claim("rememberMe", rememberMe)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public boolean isPersistentRefreshToken(String token) {
        return readRefreshToken(token).rememberMe();
    }

    public boolean isValid(String token) {
        // JwtAuthenticationFilter가 매 요청마다 부담 없이 "유효한가?"만 물어볼 때 씀 (예외를 밖으로 던지지 않음)
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public void validateOrThrow(String token) {
        validateAndGetUserId(token);
    }

    /** 재발급 시 JWT를 한 번만 파싱하여 서명·만료·사용자 ID를 함께 검사합니다. */
    public Long validateAndGetUserId(String token) {
        return readRefreshToken(token).userId();
    }

    public record RefreshIdentity(Long userId, boolean rememberMe) {}

    /** 서명 검증과 쿠키 정책 읽기를 한 번의 파싱으로 끝내며, 레거시 토큰의 유지 정책은 보존합니다. */
    public RefreshIdentity readRefreshToken(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            return new RefreshIdentity(Long.valueOf(claims.getSubject()),
                    !claims.containsKey("rememberMe") || Boolean.TRUE.equals(claims.get("rememberMe")));
        } catch (ExpiredJwtException e) {
            throw new ExpiredTokenException();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidTokenException();
        }
    }

    public Claims getClaims(String token) {
        // 서명 검증까지 통과한 토큰의 페이로드(클레임 집합)를 꺼냄
        return Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        // subject 클레임 = userId (문자열로 저장돼 있어서 Long으로 다시 파싱)
        return Long.valueOf(getClaims(token).getSubject());
    }

    public String getUsername(String token) {
        // access token 발급 시 심어둔 커스텀 클레임 "username"을 꺼냄
        return getClaims(token).get("username", String.class);
    }
}
