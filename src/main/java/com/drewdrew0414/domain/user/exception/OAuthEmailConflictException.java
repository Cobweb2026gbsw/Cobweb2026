package com.drewdrew0414.domain.user.exception;

import com.drewdrew0414.global.exception.CustomException;
import com.drewdrew0414.global.exception.ErrorCode;

public final class OAuthEmailConflictException extends CustomException {
    public OAuthEmailConflictException() {
        super(ErrorCode.OAUTH_EMAIL_CONFLICT);
    }
}
