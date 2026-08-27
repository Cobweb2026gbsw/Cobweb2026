package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.EmailVerification;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.EmailNotVerifiedException;
import com.drewdrew0414.domain.user.exception.TooManyVerificationRequestsException;
import com.drewdrew0414.domain.user.repository.EmailVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * 이메일 인증 서비스의 보안 규칙을 DB나 SMTP 서버 없이 검증하는 단위 테스트입니다.
 * 외부 의존성은 Mockito 가짜 객체로 대체하고, 요청 제한과 일회성 소비처럼 실수로 제거되기 쉬운
 * 정책이 코드 변경 뒤에도 계속 유지되는지 확인합니다.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {
    @Mock
    private EmailVerificationRepository emailVerificationRepository;

    @Mock
    private MailService mailService;

    private EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        emailVerificationService = new EmailVerificationService(emailVerificationRepository, mailService);
    }

    @Test
    void sendCode_blocksFourthRequestWithinTenMinutes() {
        when(emailVerificationRepository.countByEmailAndPurposeAndCreatedAtAfter(
                any(), any(), any(LocalDateTime.class))).thenReturn(3L);

        assertThrows(TooManyVerificationRequestsException.class,
                () -> emailVerificationService.sendCode("user@example.com", VerificationPurpose.JOIN));

        // 제한된 요청에서는 DB 저장과 실제 메일 발송이 모두 일어나지 않아야 합니다.
        verify(emailVerificationRepository, never()).save(any());
        verify(mailService, never()).sendVerificationCode(any(), any(), any());
    }

    @Test
    void consumeVerification_deletesAllRecordsAfterSuccessfulVerification() {
        EmailVerification verification = verifiedEmailVerification();
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "user@example.com", VerificationPurpose.JOIN)).thenReturn(Optional.of(verification));

        emailVerificationService.consumeVerification("user@example.com", VerificationPurpose.JOIN);

        verify(emailVerificationRepository).deleteAllByEmailAndPurpose(
                "user@example.com", VerificationPurpose.JOIN);
    }

    @Test
    void consumeVerification_rejectsUnverifiedEmail() {
        EmailVerification verification = new EmailVerification(
                "user@example.com", "12345678", VerificationPurpose.JOIN, LocalDateTime.now().plusMinutes(5));
        when(emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "user@example.com", VerificationPurpose.JOIN)).thenReturn(Optional.of(verification));

        assertThrows(EmailNotVerifiedException.class,
                () -> emailVerificationService.consumeVerification("user@example.com", VerificationPurpose.JOIN));

        verify(emailVerificationRepository, never()).deleteAllByEmailAndPurpose(any(), any());
    }

    private EmailVerification verifiedEmailVerification() {
        EmailVerification verification = new EmailVerification(
                "user@example.com", "12345678", VerificationPurpose.JOIN, LocalDateTime.now().plusMinutes(5));
        verification.markAsVerified();
        return verification;
    }
}
