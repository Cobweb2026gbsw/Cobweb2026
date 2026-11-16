package com.drewdrew0414.global.exception;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.ProblemAuthoringBodyFilter;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.*;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * API 경계에서 예외 타입을 상태·공개 코드로 변환합니다. 컨트롤러마다 catch를 반복하지 않습니다.
 * DB/외부 제공자/예상 못 한 실패의 원문·입력·토큰·소스 코드는 응답과 진단 로그에 복사하지 않습니다.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG=LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApi(ApiException error) {
        var type=error.getErrorType();
        // 5xx reason에는 외부 라이브러리의 원문이 섞일 수 있어 항상 고정 공개 안내를 사용합니다.
        String message=error.getStatusCode().is5xxServerError()?type.message():error.getReason();
        return ResponseEntity.status(error.getStatusCode()).body(ApiResponse.error(type.name(),message));
    }

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException error) {
        var code=error.getErrorCode();
        return ResponseEntity.status(code.getStatus()).body(ApiResponse.error(code.name(),code.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatus(ResponseStatusException error) {
        var type=ApiException.Type.from(error.getStatusCode());
        // 일반 라이브러리 reason도 안전한 메시지라고 가정하지 않습니다.
        return ResponseEntity.status(error.getStatusCode()).body(ApiResponse.error(type.name(),type.message()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,MissingServletRequestPartException.class,
        MethodArgumentNotValidException.class,BindException.class,jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception error) {
        if(ProblemAuthoringBodyFilter.isBodyLimitExceeded(error))return failure(ApiException.Type.PAYLOAD_TOO_LARGE);
        return failure(ApiException.Type.INVALID_REQUEST);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException error) {
        // 잘못된 입력은 400, 서버 반환값 검증 실패는 500입니다.
        return error.getStatusCode().is4xxClientError()?failure(ApiException.Type.INVALID_REQUEST):internal(error);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethod(HttpRequestMethodNotSupportedException error) {
        var builder=ResponseEntity.status(405);
        var allowed=error.getSupportedHttpMethods();
        if(allowed!=null)builder.allow(allowed.toArray(org.springframework.http.HttpMethod[]::new));
        return builder.body(ApiResponse.error("METHOD_NOT_ALLOWED",ApiException.Type.METHOD_NOT_ALLOWED.message()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaType(HttpMediaTypeNotSupportedException error) {
        return failure(ApiException.Type.UNSUPPORTED_MEDIA_TYPE);
    }
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccept(HttpMediaTypeNotAcceptableException error) {
        return failure(ApiException.Type.NOT_ACCEPTABLE);
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleSize(MaxUploadSizeExceededException error) {
        return failure(ApiException.Type.PAYLOAD_TOO_LARGE);
    }
    @ExceptionHandler({NoHandlerFoundException.class,NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingResource(Exception error) {
        return failure(ApiException.Type.RESOURCE_NOT_FOUND);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException error) {
        return failure(ApiException.Type.AUTHENTICATION_REQUIRED);
    }
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException error) {
        return failure(ApiException.Type.PERMISSION_DENIED);
    }

    @ExceptionHandler({DataIntegrityViolationException.class,ConcurrencyFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleConflict(Exception error) {
        return failure(ApiException.Type.STATE_CONFLICT);
    }
    @ExceptionHandler({DataAccessResourceFailureException.class,TransientDataAccessException.class,
        AsyncRequestTimeoutException.class})
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(Exception error) {
        return failure(ApiException.Type.SERVICE_UNAVAILABLE);
    }
    @ExceptionHandler(org.springframework.web.client.RestClientException.class)
    public ResponseEntity<ApiResponse<Void>> handleUpstream(Exception error) {
        return failure(ApiException.Type.UPSTREAM_ERROR);
    }
    @ExceptionHandler(org.springframework.mail.MailException.class)
    public ResponseEntity<ApiResponse<Void>> handleMail(Exception error) {
        return failure(ApiException.Type.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception error) {
        return ProblemAuthoringBodyFilter.isBodyLimitExceeded(error)?failure(ApiException.Type.PAYLOAD_TOO_LARGE):internal(error);
    }

    private static ResponseEntity<ApiResponse<Void>> failure(ApiException.Type type) {
        return ResponseEntity.status(type.status()).body(ApiResponse.error(type.name(),type.message()));
    }
    private static ResponseEntity<ApiResponse<Void>> internal(Exception error) {
        String errorId=UUID.randomUUID().toString();
        // 원문 메시지/원인/요청 경로에는 자격 증명이 포함될 수 있습니다. 타입과 코드 위치만 기록합니다.
        String frames=Arrays.stream(error.getStackTrace()).limit(12)
            .map(frame->frame.getClassName()+"."+frame.getMethodName()+":"+frame.getLineNumber())
            .collect(Collectors.joining(" <- "));
        LOG.error("API failure id={} type={} frames={}",errorId,error.getClass().getName(),frames);
        return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR",
            ApiException.Type.INTERNAL_ERROR.message(),errorId));
    }
}
