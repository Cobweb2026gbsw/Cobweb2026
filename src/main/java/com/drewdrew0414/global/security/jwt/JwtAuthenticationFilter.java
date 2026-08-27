package com.drewdrew0414.global.security.jwt;

import com.drewdrew0414.global.security.CustomUserDetailsService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/*
 * 매 HTTP 요청마다 한 번씩 실행되는 필터로, 요청에 담긴 JWT를 검사해서 로그인 상태를 유지시켜주는 역할을 합니다.
 * Authorization 헤더의 토큰이 유효하면 그 요청을 "인증된 사용자의 요청"으로 표시해두고,
 * 이후 컨트롤러나 다른 Security 로직이 그 인증 정보를 그대로 활용할 수 있게 만들어줍니다.
 * 세션을 안 쓰는 JWT 방식에서는 이 필터가 사실상 로그인 상태를 매 요청마다 확인해주는 역할을 대신합니다.
 */
@RequiredArgsConstructor
// 요청 하나당 딱 한 번 실행되는 필터 (매 요청마다 토큰 검사해서 로그인 상태 유지)

public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    // OncePerRequestFilter가 요구하는 유일한 메서드. 실제 필터링 로직은 전부 여기 들어감
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && jwtTokenProvider.isValid(token)) {
            String username = jwtTokenProvider.getUsername(token);
            var userDetails = customUserDetailsService.loadUserByUsername(username);

            // 이 요청을 "인증된 사용자의 요청"으로 SecurityContext에 등록
            var authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        // 토큰이 없거나 유효하지 않으면 그냥 통과 (permitAll 경로일 수도 있으니 여기서 막지 않음)

        filterChain.doFilter(request, response); // 다음 필터로 넘기기 (빼먹으면 요청이 멈춤)

    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()); // "Bearer " 접두사 제거
        }
        return null;
    }

}
