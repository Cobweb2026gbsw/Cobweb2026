package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.PasswordReset;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.InvalidResetTokenException;
import com.drewdrew0414.domain.user.exception.UserNotFoundException;
import com.drewdrew0414.domain.user.repository.PasswordResetRepository;
import com.drewdrew0414.domain.user.repository.RefreshTokenRepository;
import com.drewdrew0414.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/*
 * 비밀번호를 잊어버린 회원이 이메일 인증을 거쳐 비밀번호를 재설정하는 흐름을 담당합니다.
 * 1) requestReset: 이메일로 인증 코드 발송
 * 2) verifyCodeAndIssueToken: 코드 확인 후 1회용 resetToken 발급
 * 3) confirmReset: resetToken으로 실제 비밀번호 변경
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> PasswordResetController가 주입받아 씀

@RequiredArgsConstructor
// final 필드들을 받는 생성자를 자동 생성 (의존성 주입용)

public class PasswordResetService {
    private static final Duration RESET_TOKEN_TTL = Duration.ofMinutes(30); // resetToken 유효 시간

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetRepository passwordResetRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    // 가입 여부 확인 + 코드 발송이 하나의 흐름으로 처리돼야 함
    public void requestReset(String email) {
        userRepository.findByEmail(email).orElseThrow(UserNotFoundException::new);
        // 가입되지 않은 이메일로는 애초에 재설정 코드를 보낼 필요가 없음
        emailVerificationService.sendCode(email, VerificationPurpose.PASSWORD_RESET);
    }

    @Transactional
    // 코드 검증 + resetToken 저장이 하나의 트랜잭션으로 묶여야 함
    public String verifyCodeAndIssueToken(String email, String code) {
        emailVerificationService.verifyCode(email, code, VerificationPurpose.PASSWORD_RESET);

        // 인증 코드를 resetToken으로 교환한 순간 관련 인증 기록을 소비하여 같은 코드로 토큰을 반복 발급받지 못하게 함
        emailVerificationService.consumeVerification(email, VerificationPurpose.PASSWORD_RESET);

        User user = userRepository.findByEmail(email).orElseThrow(UserNotFoundException::new);

        String resetToken = UUID.randomUUID().toString();
        // 추측 불가능한 무작위 문자열을 1회용 토큰으로 사용
        passwordResetRepository.save(
                new PasswordReset(user.getId(), resetToken, LocalDateTime.now().plus(RESET_TOKEN_TTL)));
        return resetToken;
    }

    @Transactional
    // 비밀번호 변경 + 토큰 사용 처리가 함께 반영돼야 함 (하나만 성공하면 안 됨)
    public void confirmReset(String resetToken, String newPassword) {
        PasswordReset passwordReset = passwordResetRepository.findByResetToken(resetToken)
                .orElseThrow(InvalidResetTokenException::new);

        if (!passwordReset.isValid()) {
            // 이미 사용됐거나 만료된 토큰
            throw new InvalidResetTokenException();
        }

        User user = userRepository.findById(passwordReset.getUserId())
                .orElseThrow(UserNotFoundException::new);

        user.changePassword(passwordEncoder.encode(newPassword));
        passwordReset.markAsUsed();
        refreshTokenRepository.deleteByUserId(user.getId());
        // 비밀번호가 바뀌어도 기존 refresh token이 살아 있으면 탈취된 세션이 계속 재발급될 수 있으므로 모두 폐기
        // 둘 다 변경감지(dirty checking)로 트랜잭션 종료 시 자동 반영됨
    }
}
