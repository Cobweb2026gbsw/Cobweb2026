package com.drewdrew0414.domain.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

/*
 * 로그인 API 요청 본문(body)을 표현하는 DTO입니다.
 * 클라이언트가 보낸 JSON을 자바 객체로 받기 위한 그릇 역할만 하며, username과 password를 담습니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok) -> 컨트롤러/서비스에서 request.getUsername()처럼 꺼내 씀
public class LoginRequest {
    @NotBlank(message = "아이디를 입력해주세요.")
    // null이거나 빈 문자열("")이거나 공백만 있으면 검증 실패 -> GlobalExceptionHandler가 이 message로 400 응답
    private String username;

    @NotBlank(message = "비밀번호를 입력해주세요.")
    private String password;
}
