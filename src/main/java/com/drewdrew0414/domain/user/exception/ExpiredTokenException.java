package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * JWT의 만료 시간이 지나서 더 이상 유효하지 않을 때 발생하는 예외입니다.
 * access token이 만료되면 클라이언트는 이 예외를 응답으로 받고 refresh token으로 재발급을 시도하게 되고,
 * refresh token까지 만료됐다면 다시 로그인해야 합니다.
 * CustomException을 상속해서 ErrorCode.EXPIRED_TOKEN(401)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class ExpiredTokenException extends CustomException {
    public ExpiredTokenException() {
        super(ErrorCode.EXPIRED_TOKEN); // ErrorCode에 미리 정의해둔 401 + 메시지를 그대로 물려받음
    }

}
