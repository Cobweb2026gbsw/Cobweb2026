package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.PasswordReset;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.repository.PasswordResetRepository;
import com.drewdrew0414.domain.user.repository.RefreshTokenRepository;
import com.drewdrew0414.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 비밀번호 재설정이 비밀번호 문자열만 바꾸고 끝나지 않고, 탈취됐을 수 있는 기존 로그인 세션까지
 * 함께 끊는지 검증합니다. 이 규칙이 빠지면 공격자가 이전 refresh token으로 계속 로그인할 수 있습니다.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {
    @Mock
    private UserRepository userRepository;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private PasswordResetRepository passwordResetRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private PasswordResetService passwordResetService;

    @Test
    void confirmReset_invalidatesEveryRefreshTokenForUser() {
        PasswordReset passwordReset = new PasswordReset(42L, "reset-token", LocalDateTime.now().plusMinutes(30));
        User user = mock(User.class);
        when(passwordResetRepository.findByResetToken("reset-token")).thenReturn(Optional.of(passwordReset));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(42L);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-password");

        passwordResetService.confirmReset("reset-token", "new-password");

        verify(user).changePassword("encoded-password");
        verify(refreshTokenRepository).deleteByUserId(42L);
    }
}
