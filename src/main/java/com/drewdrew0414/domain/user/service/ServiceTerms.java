package com.drewdrew0414.domain.user.service;

import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 가입 시 보고 동의한 판본이 현재 제공하는 약관과 일치해야 합니다. */
public final class ServiceTerms {
    public static final String VERSION = "2026-11-20";

    private ServiceTerms() {}

    public static void requireCurrent(String acceptedVersion) {
        if (!VERSION.equals(acceptedVersion)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "현재 이용약관을 확인하고 동의해 주세요.");
        }
    }
}
