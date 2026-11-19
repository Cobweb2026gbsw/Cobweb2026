package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 언어는 서버 허용 목록으로 검증합니다. 명령 문자열이나 파일 경로는 제출받지 않습니다. */
@RestController @RequestMapping("/api/submissions") @RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionService service;
    public record Request(@Min(1000) int problemNumber,@NotBlank @Size(max=65536) String sourceCode,
                          @Size(max=30) String language) {}
    @PostMapping public Object submit(@Valid @RequestBody Request request,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.submit(request.problemNumber(),request.sourceCode(),Language.parse(request.language()),user));
    }
    @PostMapping("/recover")
    public ResponseEntity<ApiResponse<Long>> recover(@Valid @RequestBody Request request,
                                                     @AuthenticationPrincipal CustomUserDetails user) {
        Long id = service.recover(request.problemNumber(), request.sourceCode(), Language.parse(request.language()), user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(id));
    }
    @GetMapping public Object mine(@AuthenticationPrincipal CustomUserDetails user) { return ApiResponse.success(service.mine(user)); }
    @GetMapping("/{id}/source")
    public ResponseEntity<ApiResponse<String>> ownSource(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(service.ownSource(id,user)));
    }
    @GetMapping("/{id}") public Object detail(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.detail(id,user));
    }
}
