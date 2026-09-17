package com.drewdrew0414.global.security;

import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/*
 * Spring Security가 인증 과정에서 유저 정보를 조회할 때 사용하는 서비스입니다.
 * 주로 JwtAuthenticationFilter가 토큰에서 꺼낸 username으로 이 서비스를 호출해서
 * 실제 DB에 있는 유저 정보를 CustomUserDetails 형태로 가져오고, 그걸 인증 컨텍스트에 채워 넣습니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> JwtAuthenticationFilter가 주입받아 씀
@RequiredArgsConstructor // final 필드를 받는 생성자를 자동 생성  (의존성 주입용)
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    @Override
    // UserDetailsService 인터페이스가 요구하는 유일한 메서드. 이름 그대로 username으로 유저를 찾아 UserDetails로 반환
    public CustomUserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("존재하지 않는 사용자입니다 > " + username));
        return new CustomUserDetails(user);
    }
}
