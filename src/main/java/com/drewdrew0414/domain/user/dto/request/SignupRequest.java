package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/*
 * 회원가입 API 요청 본문을 표현하는 DTO입니다.
 * 클라이언트가 입력한 username, email, password를 받아서 UserService.signup으로 전달하는 그릇 역할을 합니다.
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

    @NotBlank(message = "비밀번호를 입력해주세요.")
    @Size(min = 8, message = "비밀번호는 8자 이상이어야합니다.")
    private String password;
}
