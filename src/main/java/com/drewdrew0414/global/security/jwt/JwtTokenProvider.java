package com.drewdrew0414.global.security.jwt;

import com.drewdrew0414.domain.user.exception.ExpiredTokenException;
import com.drewdrew0414.domain.user.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
    private final long accessTokenExpiration;
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


    public long getAccessTokenExpiration() {
        // AuthService가 TokenResponse.expiresIn을 채우거나 쿠키 만료시간을 계산할 때 씀
        return accessTokenExpiration;
    }

    public long getRefreshTokenExpiration() {
        // AuthService가 RefreshToken 엔티티의 expiresAt이나 쿠키 maxAge를 계산할 때 씀
        return refreshTokenExpiration;
    }

    public String generateAccessToken(Long userId, String username, String role) {
        // subject(sub 클레임)에 userId를 담고, username/role은 커스텀 클레임으로 추가
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key) // 위조 방지를 위해 서명 (key는 application.yaml jwt.secret 기반)
                .compact();
    }

    public String generateRefreshToken(Long userId) {
        // refresh token은 username/role 없이 userId(subject)만 담음. 재발급 시엔 DB의 RefreshToken 레코드로
        // 실제 유효성을 다시 확인하므로 굳이 클레임을 더 실을 필요가 없음
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenExpiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
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
        // reissue()처럼 "왜 실패했는지"까지 클라이언트에 알려줘야 하는 곳에서 씀
        // 만료(ExpiredJwtException)와 그 외 위조/형식 오류를 구분해서 서로 다른 예외로 던짐
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
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
