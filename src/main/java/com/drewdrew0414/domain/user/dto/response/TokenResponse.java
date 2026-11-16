package com.drewdrew0414.domain.user.dto.response;

import lombok.Builder;
import lombok.Getter;

/*
 * 로그인/재발급 성공 시 클라이언트에게 돌려주는 응답 DTO입니다.
 * 클라이언트는 이 안의 accessToken을 이후 요청의 Authorization 헤더에 실어서 인증된 요청을 보내게 됩니다.
 */
@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)
@Builder
// TokenResponse.builder().accessToken(...).tokenType(...).expiresIn(...).build() 형태로 생성할 수 있게 해줌
public class TokenResponse {
    private String accessToken;
    private String tokenType; // "Bearer" 고정값. Authorization 헤더 작성 시 "Bearer " + accessToken 형태로 씀
    private long expiresIn; // access token 만료까지 남은 시간(ms)
}
