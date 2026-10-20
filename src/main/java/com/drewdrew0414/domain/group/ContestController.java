package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/groups/{gid}/contests") @RequiredArgsConstructor
public class ContestController {
    private final ContestService contests;

    @GetMapping public Object list(@PathVariable long gid,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.list(gid,user));
    }
    @PostMapping public Object create(@PathVariable long gid,@Valid @RequestBody ContestRequests.Write request,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.create(gid,request,user));
    }
    @GetMapping("/{cid}") public Object detail(@PathVariable long gid,@PathVariable long cid,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.detail(gid,cid,user));
    }
    @PutMapping("/{cid}") public Object update(@PathVariable long gid,@PathVariable long cid,
        @Valid @RequestBody ContestRequests.Write request,@AuthenticationPrincipal CustomUserDetails user) {
        contests.update(gid,cid,request,user); return ApiResponse.success(null);
    }
    @GetMapping("/{cid}/problems") public Object problems(@PathVariable long gid,@PathVariable long cid,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.problems(gid,cid,user));
    }
    @GetMapping("/{cid}/problems/{number}") public Object problem(@PathVariable long gid,@PathVariable long cid,
        @PathVariable int number,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.problem(gid,cid,number,user));
    }
    @GetMapping("/{cid}/me") public Object mine(@PathVariable long gid,@PathVariable long cid,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.mine(gid,cid,user));
    }
    @GetMapping("/{cid}/results") public Object results(@PathVariable long gid,@PathVariable long cid,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.results(gid,cid,user));
    }
    @GetMapping("/{cid}/submissions") public Object submissions(@PathVariable long gid,@PathVariable long cid,
        @RequestParam long userId,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.submissions(gid,cid,userId,user));
    }
    @PostMapping("/{cid}/events") public Object event(@PathVariable long gid,@PathVariable long cid,
        @Valid @RequestBody ContestRequests.Event event,@AuthenticationPrincipal CustomUserDetails user) {
        contests.event(gid,cid,event,user); return ApiResponse.success(null);
    }
    @GetMapping("/{cid}/events") public Object events(@PathVariable long gid,@PathVariable long cid,
        @RequestParam(required=false) Long userId,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(contests.events(gid,cid,userId,user));
    }
}
