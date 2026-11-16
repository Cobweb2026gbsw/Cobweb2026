package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/*
 * 실제 이메일 발송(SMTP)을 전담하는 컴포넌트입니다.
 * EmailVerificationService가 인증 코드를 생성한 뒤 이 클래스를 통해 메일로 내보냅니다.
 */
@Component
// 스프링이 관리하는 빈으로 등록 -> EmailVerificationService가 주입받아 씀

public class MailService {
    private final JavaMailSender mailSender;
    private final String senderAddress;

    public MailService(JavaMailSender mailSender, @Value("${spring.mail.username:}") String senderAddress) {
        this.mailSender = mailSender;
        this.senderAddress = senderAddress;
    }

    public void sendVerificationCode(String to, String code, VerificationPurpose purpose) {
        if (senderAddress.isBlank()) {
            throw new MailAuthenticationException("SMTP sender is not configured.");
        }
        String subject = purpose == VerificationPurpose.JOIN
                ? "[Cobweb] 회원가입 이메일 인증 코드"
                : "[Cobweb] 비밀번호 재설정 인증 코드";

        SimpleMailMessage message = new SimpleMailMessage();
        // 첨부파일/HTML이 필요 없는 단순 텍스트 메일이라 SimpleMailMessage로 충분함
        message.setFrom(senderAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText("인증 코드: " + code + "\n\n5분 이내에 입력해주세요. 본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.");
        mailSender.send(message);
        // 여기서 실제 SMTP 서버(Gmail)로 연결해서 메일을 전송함. 인증 실패 등은 MailException(unchecked)으로 던져짐
    }
}
