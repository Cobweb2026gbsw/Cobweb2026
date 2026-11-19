package com.drewdrew0414.global.security;

import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/*
 * Spring Security가 인증 과정에서 유저 정보를 조회할 때 사용하는 서비스입니다.
 * 로그인 이름은 변경할 수 있으므로 JWT 인증에서는 서명된 subject의 불변 회원 ID로 조회합니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> JwtAuthenticationFilter가 주입받아 씀
@RequiredArgsConstructor // final 필드를 받는 생성자를 자동 생성  (의존성 주입용)
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    public CustomUserDetails loadUserById(long id) {
        return userRepository.findById(id).map(CustomUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 계정입니다."));
    }

    @Override
    // UserDetailsService 인터페이스가 요구하는 유일한 메서드. 이름 그대로 username으로 유저를 찾아 UserDetails로 반환
    public CustomUserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 사용자입니다 > " + username));
        return new CustomUserDetails(user);
    }
}
