package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 문제 API 진입점. 공개 조회와 인증된 작성/검수를 서비스의 권한 검사로 분리합니다. */
@RestController @RequestMapping("/api/problems") @RequiredArgsConstructor
public class ProblemController {
    private final ProblemService service;
    @GetMapping
    public Object list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search) {
        return ApiResponse.success(service.list(page,search));
    }
    @GetMapping("/permissions")
    public Object permissions(@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.permissions(user));
    }
    @GetMapping("/mine")
    public Object mine(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
                       @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.mine(page,search,user));
    }
    @GetMapping("/{number}")
    public Object detail(@PathVariable int number,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.detail(number,user));
    }
    @GetMapping("/{number}/edit")
    public Object edit(@PathVariable int number,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.edit(number,user));
    }
    @PostMapping
    public Object create(@Valid @RequestBody ProblemRequests.Write request,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.create(request,user));
    }
    @PutMapping("/{number}")
    public Object update(@PathVariable int number,@Valid @RequestBody ProblemRequests.Write request,@AuthenticationPrincipal CustomUserDetails user) {
        service.update(number,request,user); return ApiResponse.success(null);
    }
    @PostMapping("/{number}/review-request")
    public Object requestReview(@PathVariable int number,@AuthenticationPrincipal CustomUserDetails user) {
        service.requestReview(number,user); return ApiResponse.success(null);
    }
    @PostMapping("/{number}/review")
    public Object review(@PathVariable int number,@Valid @RequestBody ProblemRequests.Review request,@AuthenticationPrincipal CustomUserDetails user) {
        service.review(number,request,user); return ApiResponse.success(null);
    }
}
