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
    // 숫자 경로나 페이지에 문자열을 보낸 경우도 500이 아니라 잘못된 요청입니다.
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("요청 매개변수 형식을 확인해주세요."));
    }
    // JSON 문법이나 필드 타입이 잘못된 요청은 서버 장애가 아닌 클라이언트 오류입니다.
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ApiResponse.error("요청 JSON 형식을 확인해주세요."));
    }
    // 새 API의 400/403/404/409 상태를 일반 500 처리에 흡수하지 않고 그대로 전달합니다.
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatus(org.springframework.web.server.ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(ApiResponse.error(
            e.getReason() == null ? "요청을 처리할 수 없습니다." : e.getReason()));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(org.springframework.dao.DataIntegrityViolationException e) {
        return ResponseEntity.status(409).body(ApiResponse.error("중복되거나 참조 조건에 맞지 않는 데이터입니다."));
    }
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
