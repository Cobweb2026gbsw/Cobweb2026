package com.drewdrew0414.global.security;

import com.drewdrew0414.domain.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/*
 * Spring Security가 인증된 사용자를 다루기 위해 요구하는 UserDetails 규격에 맞춰
 * 우리 프로젝트의 User 엔티티를 감싸주는 어댑터 클래스입니다.
 * Security 프레임워크 입장에서는 이 클래스를 통해서만 유저 정보(아이디, 비밀번호, 권한)를 들여다보게 되고,
 * 덕분에 User 엔티티 자체는 Security에 대해 몰라도 됩니다.
 */
@Getter
// user 필드의 getter를 자동 생성 (Lombok)
public class CustomUserDetails implements UserDetails {
    private final User user;

    public CustomUserDetails(User user) {
        this.user = user;
    }

    public Long getUserId() { // 컨트롤러에서 @AuthenticationPrincipal로 받아 바로 쓰기 편하게
        return user.getId();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // User.role(enum)을 "ROLE_USER" 같은 문자열 권한 하나로 변환해서 돌려줌
        return List.of(new SimpleGrantedAuthority(user.getRole().getAuthority()));
    }

    @Override
    public String getPassword() {
        // Security가 폼 로그인 등에서 비밀번호를 비교할 때 쓰는 값. 우리는 AuthService에서 직접 matches()로 검증하므로
        // 이 프로젝트에서는 사실상 안 쓰이지만, UserDetails 인터페이스라 구현은 해야 함
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        // 계정 만료 기능은 따로 두지 않아서 항상 true (만료 안 됨)
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // User.isLoginable()과 동일한 기준(status == ACTIVE)으로 잠금 여부를 판단
        return user.isLoginable();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // 비밀번호 만료 기능은 따로 두지 않아서 항상 true
        return true;
    }

    @Override
    public boolean isEnabled() {
        // 계정 활성화 여부도 isLoginable()과 동일한 기준을 재사용
        return user.isLoginable();
    }
}
