package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Python 제출만 허용합니다. 언어를 명령 문자열로 넘기지 않아 명령어 삽입을 막습니다. */
@RestController @RequestMapping("/api/submissions") @RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionService service;
    public record Request(@Min(1000) int problemNumber,@NotBlank @Size(max=65536) String sourceCode) {}
    @PostMapping public Object submit(@Valid @RequestBody Request request,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.submit(request.problemNumber(),request.sourceCode(),user));
    }
    @GetMapping public Object mine(@AuthenticationPrincipal CustomUserDetails user) { return ApiResponse.success(service.mine(user)); }
    @GetMapping("/{id}") public Object detail(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.detail(id,user));
    }
}
