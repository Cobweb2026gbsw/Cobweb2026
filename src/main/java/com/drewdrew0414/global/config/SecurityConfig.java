package com.drewdrew0414.global.config;

import com.drewdrew0414.global.security.CustomUserDetailsService;
import com.drewdrew0414.global.security.jwt.JwtAuthenticationFilter;
import com.drewdrew0414.global.security.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/*
 * 이 프로젝트의 Spring Security 전체 동작 방식을 정의하는 설정 클래스입니다.
 * 어떤 API가 로그인 없이 접근 가능한지(permitAll), 어떤 요청은 인증이 필요한지,
 * 세션을 쓸지 말지, 비밀번호를 어떤 방식으로 암호화할지 등 인증/인가의 큰 틀을 여기서 정합니다.
 * 직접 만든 JwtAuthenticationFilter를 이 설정에 등록해서 매 요청마다 토큰을 검사하게 만듭니다.
 */
@Configuration
// 이 클래스 안의 @Bean 메서드들이 반환하는 객체를 스프링 컨테이너가 관리하게 함

@RequiredArgsConstructor
// final 필드(jwtTokenProvider, customUserDetailsService)를 받는 생성자를 자동 생성 (의존성 주입용)

class SecurityConfig {
    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;

    @Bean
    // 스프링이 관리하는 SecurityFilterChain 빈으로 등록 -> 모든 HTTP 요청이 이 체인을 통과하게 됨
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                // JWT 방식은 쿠키/세션 기반이 아니라 CSRF 공격 대상이 아니므로 꺼둠
                .formLogin(AbstractHttpConfigurer::disable)
                // 스프링이 기본 제공하는 로그인 폼을 안 씀 (우리는 AuthController에서 직접 로그인 API를 만듦)
                .httpBasic(AbstractHttpConfigurer::disable)
                // 브라우저 팝업으로 아이디/비밀번호 묻는 기본 인증 방식도 안 씀
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 세션을 아예 만들지 않음 -> 로그인 상태는 매 요청마다 JWT로만 판단
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/logout").authenticated()
                        // 로그아웃은 현재 사용자 ID의 refresh token을 삭제하므로 반드시 access token으로 본인을 확인
                        .requestMatchers("/api/auth/**").permitAll()
                        // 회원가입/로그인/재발급/비밀번호 재설정/OAuth 콜백은 로그인 전에 호출될 수 있어 공개
                        .requestMatchers("/api/email-verifications/**").permitAll()
                        // 이메일 인증 코드 발송/확인도 로그인 전에 호출되므로 허용
                        .requestMatchers("/", "/index.html", "/favicon.ico").permitAll() // 테스트용 정적 페이지
                        .anyRequest().authenticated())
                // 그 외 모든 요청은 유효한 JWT(=인증된 사용자)가 있어야만 통과
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                        // 스프링 시큐리티의 기본 로그인 필터 자리보다 앞에 우리 JWT 필터를 끼워 넣음
                );
        return http.build();
    }

    @Bean
    // 비밀번호 암호화에 쓸 PasswordEncoder를 빈으로 등록 -> UserService/AuthService가 주입받아 씀
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
        // BCrypt: 매번 다른 salt를 섞어 해시하는 단방향 암호화 방식, 비밀번호 원문은 절대 복원 불가
    }
}
