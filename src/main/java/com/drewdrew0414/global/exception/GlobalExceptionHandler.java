package com.drewdrew0414.global.exception;

import com.drewdrew0414.global.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
 * 애플리케이션 전역에서 발생하는 예외를 한 곳에서 처리하는 클래스입니다.
 * 컨트롤러마다 try-catch를 반복하지 않아도, 여기서 CustomException이나 검증 실패 예외를 잡아서
 * 일관된 형식(ApiResponse)의 에러 응답으로 변환해주는 역할을 합니다.
 */
@RestControllerAdvice
// 모든 컨트롤러에서 발생하는 예외를 여기 한 곳에서 잡음
public class GlobalExceptionHandler {
    @ExceptionHandler(CustomException.class)
    // CustomException을 상속한 모든 하위 예외를 다 잡음
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e) {
        return ResponseEntity
                .status(e.getErrorCode().getStatus())    // 예외가 들고 있는 ErrorCode의 상태코드 그대로 사용
                .body(ApiResponse.error(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    // @Valid가 붙은 DTO의 검증(예: @NotBlank, @Email)에 실패하면 스프링이 이 예외를 던짐
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage) // @NotBlank(message = "...") 등에 적어둔 메시지
                .orElse("Wrong Requestion.");
        return ResponseEntity.badRequest().body(ApiResponse.error(message));
    }

    @ExceptionHandler(Exception.class) // 예상 못 한 예외까지 마지막에 다 받아서 500으로 응답
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        return ResponseEntity.internalServerError().body(ApiResponse.error("Internal Server Error."));
    }
}
