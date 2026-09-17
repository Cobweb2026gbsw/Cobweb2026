package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/*
 * 회원가입 API 요청 본문을 표현하는 DTO입니다.
 * 아이디·이메일·비밀번호와 이메일 소유 증명 코드를 UserService.signup으로 전달합니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
public class SignupRequest {
    @NotBlank(message = "아이디를 입력해주세요.")
    @Size(max = 16, message = "아이디는 16자 이하여야 합니다.")
    // users.username이 VARCHAR(16)이라 DB 컬럼 길이와 맞춤
    private String username;

    @NotBlank(message = "이메일을 입력해주세요.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    // "aaa@bbb.ccc" 형태인지 형식만 검사 (실제 소유 여부는 EmailVerificationService가 별도로 검증)
    private String email;

    @NotBlank(message = "이메일 인증 코드를 입력해주세요.")
    @Pattern(regexp = "[0-9]{8}", message = "이메일 인증 코드는 숫자 8자리여야 합니다.")
    // 이메일의 전역 verified 상태만으로는 이 가입 요청자가 이메일 소유자인지 알 수 없습니다.
    // 인증 확인 단계에서 사용한 코드를 최종 가입 요청에서도 확인합니다.
    private String verificationCode;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 8자 이상이어야합니다.")
    private String password;
}
