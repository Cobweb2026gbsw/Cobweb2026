package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 운영자 목록과 비공개 편집 자료는 서비스에서 매번 역할을 확인합니다. */
@RestController @RequestMapping("/api/admin/problems") @RequiredArgsConstructor
public class AdminProblemController {
    private final ProblemService service;

    @GetMapping
    public Object list(@RequestParam(defaultValue="0") int page,
                       @RequestParam(defaultValue="") String search,
                       @RequestParam(defaultValue="") String status,
                       @RequestParam(defaultValue="") String visibility,
                       @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.adminList(page,search,status,visibility,user));
    }
    @GetMapping("/{number}")
    public Object detail(@PathVariable int number,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.adminDetail(number,user));
    }
    @PatchMapping("/{number}/visibility")
    public Object visibility(@PathVariable int number,@Valid @RequestBody ProblemRequests.Visibility request,
                             @AuthenticationPrincipal CustomUserDetails user) {
        service.setVisibility(number,request,user);
        return ApiResponse.success(null);
    }
}
