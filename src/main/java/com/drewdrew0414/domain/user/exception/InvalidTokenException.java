package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * JWT의 서명이 위조됐거나 형식이 잘못돼서 파싱/검증 자체에 실패했을 때 발생하는 예외입니다.
 * JwtAuthenticationFilter가 요청을 검사할 때나, 토큰 재발급(reissue) 과정에서
 * JwtTokenProvider.validateToken()이 실패하면 이 예외를 던지게 됩니다.
 * CustomException을 상속해서 ErrorCode.INVALID_TOKEN(401)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class InvalidTokenException extends CustomException {
    public InvalidTokenException() {
        super(ErrorCode.INVALID_TOKEN); // ErrorCode에 미리 정의해둔 401 + 메시지를 그대로 물려받음
    }

}
