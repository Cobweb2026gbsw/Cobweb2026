package com.drewdrew0414.global.common;

import lombok.Getter;

/*
 * 모든 API 응답을 같은 형식으로 감싸주는 공통 응답 클래스입니다.
 * 성공/실패 여부, 실제 데이터, 메시지를 항상 같은 구조로 내려줘서
 * 프론트엔드가 응답을 파싱하는 방식을 API마다 다르게 신경 쓰지 않아도 되게 해줍니다.
 */
@Getter
// success/data/message 필드의 getter를 자동 생성 (Lombok)
public class ApiResponse<T> {
    // <T>는 클래스를 정의할 때는 어떤 데이터 타입이 들어올지 모르니, 일단 임시로 이름을 붙여둔 타입 변수를 뜻한다.
    private final boolean success;
    private final T data;
    private final String message;

    private ApiResponse(boolean success, T data, String message) {
        // private 생성자 -> 바깥에서는 new로 못 만들고, 반드시 아래 success/error 정적 팩토리로만 만들게 강제
        this.success = success;
        this.data = data;
        this.message = message;
    }

    public static <T> ApiResponse<T> success(T data) {
        // 성공 응답: success=true, message는 비워둠
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        // 실패 응답: success=false, data는 비워둠 (GlobalExceptionHandler가 여기로 감쌈)
        return new ApiResponse<>(false, null, message);
    }
}
