package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.request.SignupRequest;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.DuplicateEmailException;
import com.drewdrew0414.domain.user.exception.EmailNotVerifiedException;
import com.drewdrew0414.domain.user.exception.VerificationCodeMismatchException;
import com.drewdrew0414.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 이메일 소유 증명 전에는 중복 여부를 조회하지 않는 가입 순서를 DB 없이 검증합니다. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    private static final String EMAIL = "member@example.invalid";
    private static final String CODE = "12345678";

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailVerificationService emailVerificationService;
    @InjectMocks UserService userService;

    @Test
    void unverifiedEmailDoesNotReachDuplicateLookup() {
        SignupRequest request = signupRequest();

        assertThrows(EmailNotVerifiedException.class, () -> userService.signup(request));

        verify(userRepository, never()).existsByEmail(anyString());
        verify(emailVerificationService, never()).verifyCode(anyString(), anyString(), any());
        verify(emailVerificationService, never()).consumeVerification(anyString(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void wrongCodeDoesNotReachDuplicateLookup() {
        SignupRequest request = signupRequest();
        when(emailVerificationService.isVerified(EMAIL, VerificationPurpose.JOIN)).thenReturn(true);
        doThrow(new VerificationCodeMismatchException()).when(emailVerificationService)
                .verifyCode(EMAIL, CODE, VerificationPurpose.JOIN);

        assertThrows(VerificationCodeMismatchException.class, () -> userService.signup(request));

        verify(userRepository, never()).existsByEmail(anyString());
        verify(emailVerificationService, never()).consumeVerification(anyString(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void validCodeForExistingEmailReturnsConflictWithoutConsuming() {
        SignupRequest request = signupRequest();
        when(emailVerificationService.isVerified(EMAIL, VerificationPurpose.JOIN)).thenReturn(true);
        when(userRepository.existsByEmail(EMAIL)).thenReturn(true);

        assertThrows(DuplicateEmailException.class, () -> userService.signup(request));

        InOrder order = inOrder(userRepository, emailVerificationService);
        order.verify(emailVerificationService).isVerified(EMAIL, VerificationPurpose.JOIN);
        order.verify(emailVerificationService).verifyCode(EMAIL, CODE, VerificationPurpose.JOIN);
        order.verify(userRepository).existsByEmail(EMAIL);
        verify(emailVerificationService, never()).consumeVerification(anyString(), any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void successfulSignupVerifiesBeforeDuplicateCheckThenConsumesAndSaves() {
        SignupRequest request = signupRequest();
        when(emailVerificationService.isVerified(EMAIL, VerificationPurpose.JOIN)).thenReturn(true);
        when(passwordEncoder.encode("new-password")).thenReturn("encoded-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 42L);
            return user;
        });

        assertEquals(42L, userService.signup(request));

        InOrder order = inOrder(userRepository, emailVerificationService, passwordEncoder);
        order.verify(emailVerificationService).verifyCode(EMAIL, CODE, VerificationPurpose.JOIN);
        order.verify(userRepository).existsByEmail(EMAIL);
        order.verify(emailVerificationService).consumeVerification(EMAIL, VerificationPurpose.JOIN);
        order.verify(passwordEncoder).encode("new-password");
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        order.verify(userRepository).save(saved.capture());
        assertEquals(EMAIL, saved.getValue().getEmail());
        assertEquals("encoded-password", saved.getValue().getPassword());
    }

    private SignupRequest signupRequest() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "username", "fresh-member");
        ReflectionTestUtils.setField(request, "email", EMAIL);
        ReflectionTestUtils.setField(request, "verificationCode", CODE);
        ReflectionTestUtils.setField(request, "password", "new-password");
        return request;
    }
}
