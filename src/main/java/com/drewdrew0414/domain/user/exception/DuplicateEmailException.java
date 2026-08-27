package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 회원가입 시 입력한 이메일이 이미 사용 중일 때 발생하는 예외입니다.
 * UserService.signup()에서 userRepository.existsByEmail()이 true를 반환하면 이 예외를 던지게 됩니다.
 * CustomException을 상속해서 ErrorCode.DUPLICATE_EMAIL(409)에 해당하는 상태코드와 메시지를 갖습니다.
 */
public class DuplicateEmailException extends CustomException {
    public DuplicateEmailException() {
        super(ErrorCode.DUPLICATE_EMAIL); // ErrorCode에 미리 정의해둔 409 + 메시지를 그대로 물려받음
    }
}
