package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 계정 상태(UserStatus)가 BANNED/STOPPED/TIMEOUT이라 로그인 자체가 허용되지 않을 때 발생하는 예외입니다.
 * AuthService.login()에서 비밀번호를 검증하기 전에 먼저 이 상태부터 확인해서,
 * 정지된 계정은 비밀번호가 맞아도 로그인이 통과되지 않도록 막는 역할을 합니다.
 * CustomException을 상속해서 ErrorCode.BANNED_USER(403)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class BannedUserException extends CustomException {
    public BannedUserException() {
        super(ErrorCode.BANNED_USER); // ErrorCode에 미리 정의해둔 403 + 메시지를 그대로 물려받음
    }

}
