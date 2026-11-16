package com.drewdrew0414.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class MailServiceTest {
    private final JavaMailSender sender = mock(JavaMailSender.class);

    @Test
    void usesTheConfiguredSmtpAccountAsTheFromAddress() {
        MailService service = new MailService(sender, "mailer@example.com");

        service.sendVerificationCode("reader@example.com", "123456", VerificationPurpose.JOIN);

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(mail.capture());
        assertThat(mail.getValue().getFrom()).isEqualTo("mailer@example.com");
        assertThat(mail.getValue().getTo()).containsExactly("reader@example.com");
    }

    @Test
    void refusesToSendWhenTheSmtpSenderIsMissing() {
        MailService service = new MailService(sender, "");

        assertThatThrownBy(() ->
            service.sendVerificationCode("reader@example.com", "123456", VerificationPurpose.JOIN))
            .isInstanceOf(MailAuthenticationException.class);
        verifyNoInteractions(sender);
    }
}
