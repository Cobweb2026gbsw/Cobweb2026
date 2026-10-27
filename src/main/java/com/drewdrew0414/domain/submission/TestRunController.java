package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 실행 요청은 DB 큐에만 접수합니다. 웹 프로세스는 Docker에 접근하지 않습니다. */
@RestController @RequiredArgsConstructor
public class TestRunController {
    private final TestRunService tests;
    @PostMapping("/api/problems/{number}/test-runs")
    public Object create(@PathVariable int number,@RequestBody TestRunService.Request request,
                         @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(tests.create(number,request,user));
    }
    @GetMapping("/api/test-runs/{id}")
    public Object result(@PathVariable long id,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(tests.detail(id,user));
    }
}
