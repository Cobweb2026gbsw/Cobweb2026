package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.EmailVerification;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.EmailVerificationNotFoundException;
import com.drewdrew0414.domain.user.exception.EmailNotVerifiedException;
import com.drewdrew0414.domain.user.exception.VerificationCodeExpiredException;
import com.drewdrew0414.domain.user.exception.VerificationCodeMismatchException;
import com.drewdrew0414.domain.user.exception.TooManyVerificationRequestsException;
import com.drewdrew0414.domain.user.repository.EmailVerificationRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;

/*
 * 이메일 인증 코드의 발급/검증을 담당하는 서비스입니다.
 * 회원가입(JOIN), 비밀번호 재설정(PASSWORD_RESET) 두 목적 모두 이 서비스를 공통으로 사용합니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> UserService, PasswordResetService, EmailVerificationController가 주입받아 씀

@RequiredArgsConstructor
// final 필드들을 받는 생성자를 자동 생성 (의존성 주입용)

public class EmailVerificationService {
    private static final int CODE_LENGTH = 8; // email_verifications.code가 CHAR(8)이라 자릿수를 맞춤
    private static final Duration CODE_TTL = Duration.ofMinutes(5); // 코드 유효 시간
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(10); // 발송 횟수를 계산할 시간 범위
    private static final long MAX_REQUESTS_PER_WINDOW = 3; // 이메일+목적별 10분 동안 최대 3회

    private final EmailVerificationRepository emailVerificationRepository;
    private final MailService mailService;
    private final SecureRandom random = new SecureRandom();
    // Random 대신 SecureRandom -> 인증 코드처럼 예측 불가능해야 하는 값에는 암호학적으로 안전한 난수 생성기를 씀

    @Transactional
    // 코드 저장과 메일 발송을 하나로 묶어서, 메일 발송이 실패하면 저장도 같이 롤백되게 함
    public void sendCode(String email, VerificationPurpose purpose) {
        long recentRequestCount = emailVerificationRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, LocalDateTime.now().minus(REQUEST_WINDOW));
        if (recentRequestCount >= MAX_REQUESTS_PER_WINDOW) {
            // 메일을 만들거나 저장하기 전에 막아, 제한된 요청이 DB와 SMTP 서버에 추가 부담을 주지 않게 함
            throw new TooManyVerificationRequestsException();
        }

        String code = generateCode();
        emailVerificationRepository.save(
                new EmailVerification(email, code, purpose, LocalDateTime.now().plus(CODE_TTL)));
        mailService.sendVerificationCode(email, code, purpose);
    }

    @Transactional
    // verified 플래그를 바꾸는 변경이 즉시 반영/영속화돼야 함
    public void verifyCode(String email, String code, VerificationPurpose purpose) {
        EmailVerification verification = emailVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(EmailVerificationNotFoundException::new);
        // 코드를 요청한 적 자체가 없는 경우

        if (!verification.getCode().equals(code)) {
            throw new VerificationCodeMismatchException();
        }
        if (verification.isExpired()) {
            throw new VerificationCodeExpiredException();
        }

        verification.markAsVerified();
        // 변경감지(dirty checking) -> 트랜잭션이 끝날 때 JPA가 알아서 UPDATE 쿼리를 날려줌 (save() 호출 불필요)
    }

    public boolean isVerified(String email, VerificationPurpose purpose) {
        // UserService.signup()이 가입을 허용하기 전에 "이 이메일이 이미 인증을 마쳤는지"만 가볍게 확인할 때 씀
        return emailVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .map(EmailVerification::isVerified)
                .orElse(false); // 요청 기록 자체가 없으면 당연히 인증 안 된 것으로 취급
    }

    @Transactional
    public void consumeVerification(String email, VerificationPurpose purpose) {
        // 가입이나 재설정 절차에서 검증 결과를 실제로 사용한 시점에 관련 기록을 모두 제거합니다.
        // 최신 기록만 지우면 그보다 오래된 verified 기록이 다시 최신 기록이 되어 재사용될 수 있으므로 전부 삭제합니다.
        if (!isVerified(email, purpose)) {
            throw new EmailNotVerifiedException();
        }
        emailVerificationRepository.deleteAllByEmailAndPurpose(email, purpose);
    }

    private String generateCode() {
        // 0~9 숫자만 8자리 뽑아서 이어붙임 (예: "04829173")
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
