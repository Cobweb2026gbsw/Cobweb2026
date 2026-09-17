package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * username(또는 id)으로 유저를 찾았는데 존재하지 않을 때 발생하는 예외입니다.
 * AuthService.login()에서 UserRepository.findByUsername()의 결과가 비어있을 때 이 예외를 던지게 되고,
 * 동시에 UserLog에 FAIL_USER_NOT_FOUND로 시도 기록을 남기게 됩니다.
 * CustomException을 상속해서 ErrorCode.USER_NOT_FOUND(404)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class UserNotFoundException extends CustomException {
    public UserNotFoundException() {
        super(ErrorCode.USER_NOT_FOUND); // ErrorCode에 미리 정의해둔 404 + 메시지를 그대로 물려받음
    }

}
