package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 문제 API 진입점. 공개 조회와 인증된 작성/검수를 서비스의 권한 검사로 분리합니다. */
@RestController @RequestMapping("/api/problems") @RequiredArgsConstructor
public class ProblemController {
    private final ProblemService service;
    @GetMapping
    public Object listResponse(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
                       @RequestParam(required=false) List<String> difficulty,
                       @RequestParam(defaultValue="all") String solved,
                       @RequestParam(defaultValue="false") boolean pagination,
                       @AuthenticationPrincipal CustomUserDetails user) {
        return pagination?ApiResponse.success(service.listPage(page,search,difficulty,solved,user)):
            list(page,search,difficulty,solved,user);
    }
    /** 기존 Java 호출과 pagination 생략 시의 배열 응답을 보존합니다. */
    public Object list(int page,String search,List<String> difficulty,String solved,CustomUserDetails user) {
        return ApiResponse.success(service.list(page,search,difficulty,solved,user));
    }
    @GetMapping("/permissions")
    public Object permissions(@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(service.permissions(user));
    }
    @GetMapping("/mine")
    public Object mineResponse(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
                       @RequestParam(required=false) List<String> difficulty,
                       @RequestParam(defaultValue="all") String solved,
                       @RequestParam(defaultValue="false") boolean pagination,
                       @AuthenticationPrincipal CustomUserDetails user) {
        return pagination?ApiResponse.success(service.minePage(page,search,difficulty,solved,user)):
            mine(page,search,difficulty,solved,user);
    }
    public Object mine(int page,String search,List<String> difficulty,String solved,CustomUserDetails user) {
        return ApiResponse.success(service.mine(page,search,difficulty,solved,user));
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
