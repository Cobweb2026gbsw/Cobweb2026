package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

/*
 * 소셜 로그인 제공자가 이메일을 내려주지 않았거나 검증된 이메일을 찾지 못했을 때 발생합니다.
 * email이 null인 계정을 조용히 생성하면 비밀번호 재설정과 계정 중복 판정이 깨질 수 있으므로,
 * 필수 사용자 정보가 확보되지 않은 상태에서는 회원가입을 완료하지 않습니다.
 */
public class OAuthEmailNotFoundException extends CustomException {
    public OAuthEmailNotFoundException() {
        super(ErrorCode.OAUTH_EMAIL_NOT_FOUND);
    }
}
