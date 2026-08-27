package com.drewdrew0414.global.exception;

import lombok.Getter;

/*
 * 프로젝트의 모든 커스텀 예외가 공통으로 상속하는 기반 클래스입니다.
 * UserNotFoundException, PasswordMismatchException처럼 상황별로 나뉜 예외들이 전부 이 클래스를 상속하고,
 * 각자 자신에게 해당하는 ErrorCode(상태코드+메시지) 하나씩을 들고 있게 됩니다.
 * GlobalExceptionHandler는 이 CustomException 타입 하나만 잡으면 되기 때문에,
 * 상황별 예외 클래스가 계속 늘어나도 에러 응답 형식은 항상 일관되게 유지됩니다.
 */
@Getter
// errorCode 필드의 getter를 자동 생성 (Lombok) -> GlobalExceptionHandler가 e.getErrorCode()로 꺼내 씀
public class CustomException extends RuntimeException {
    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        // super은 부모 클래스인 RuntimeException의 생성자를 호출하는 것을 의미.
        // Java에서 예외 클래스를 직접 만들 때는 보통 표준 예외인 RuntimeException을 상속받아서 만든다.
        // 이때 RuntimeException 부모 클래스는 내부적으로 예외 메시지를 받아 저장하고,
        // 나중에 e.getMessage()나 콘솔 로그를 출력할 때 이 메시지를 활용한다.
        this.errorCode = errorCode;
    }
}
