package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.domain.user.dto.request.SignupRequest;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import com.drewdrew0414.domain.user.exception.DuplicateEmailException;
import com.drewdrew0414.domain.user.exception.DuplicateUsernameException;
import com.drewdrew0414.domain.user.exception.EmailNotVerifiedException;
import com.drewdrew0414.domain.user.repository.UserRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/*
 * 회원가입 등 User 엔티티의 생성/관리를 담당하는 서비스입니다.
 * 로그인 기능만으로는 테스트할 계정이 없기 때문에, 로그인을 만들기 전에 먼저 회원가입 로직이 필요합니다.
 * 아이디/이메일 중복을 막고, 비밀번호를 평문이 아닌 해시값으로 변환해서 저장하는 역할을 합니다.
 */
@Service
// 스프링이 관리하는 서비스 빈으로 등록 -> AuthController가 주입받아 씀

@RequiredArgsConstructor
// final 필드들을 받는 생성자를 자동 생성 (의존성 주입용)

public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;

    @Transactional
    // 중복 체크 + 저장이 하나의 트렌잭션으로 묶여야 함
    public Long signup(SignupRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateUsernameException();
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateEmailException();
        }
        if (!emailVerificationService.isVerified(request.getEmail(), VerificationPurpose.JOIN)) {
            // 이 이메일로 발송된 JOIN 목적 인증 코드가 확인(verified)된 적이 없으면 가입 자체를 막음
            throw new EmailNotVerifiedException();
        }

        // 확인된 이메일 인증 결과는 이번 회원가입에서 한 번만 사용합니다.
        // 아래 사용자 저장이 실패하면 @Transactional에 의해 인증 기록 삭제도 함께 롤백됩니다.
        emailVerificationService.consumeVerification(request.getEmail(), VerificationPurpose.JOIN);

        User user = User.builder(). // provider/providerId는 안 넘김 -> 일반 회원가입은 소셜 계정이 아니므로 null로 남음
                username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                // 평문 비밀번호를 절대 그대로 저장하지 않고 BCrypt로 해시해서 저장
                .build();

        return userRepository.save(user).getId();
    }
}
