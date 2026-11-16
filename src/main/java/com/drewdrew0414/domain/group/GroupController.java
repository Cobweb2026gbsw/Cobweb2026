package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.common.ApiResponse;
import com.drewdrew0414.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/groups") @RequiredArgsConstructor
public class GroupController {
    private final GroupService groups;

    @GetMapping public Object list(@RequestParam(defaultValue="0") int page,
                                   @RequestParam(defaultValue="") String search) {
        return ApiResponse.success(groups.publicList(page,search));
    }
    @GetMapping("/mine") public Object mine(@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.mine(user));
    }
    @PostMapping public Object create(@Valid @RequestBody GroupRequests.Write request,
                                      @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.create(request,user));
    }
    @GetMapping("/{gid}") public Object detail(@PathVariable long gid,
                                               @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.detail(gid,user));
    }
    @GetMapping("/{gid}/problems") public Object problems(@PathVariable long gid,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.problems(gid,page,search,user));
    }
    @GetMapping("/{gid}/problems/review-queue") public Object reviewQueue(@PathVariable long gid,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="") String search,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.reviewQueue(gid,page,search,user));
    }
    @PostMapping("/{gid}/join") public Object join(@PathVariable long gid,
        @AuthenticationPrincipal CustomUserDetails user) {
        groups.join(gid,user); return ApiResponse.success(null);
    }
    @PostMapping("/{gid}/leave") public Object leave(@PathVariable long gid,
        @AuthenticationPrincipal CustomUserDetails user) {
        groups.leave(gid,user); return ApiResponse.success(null);
    }
    @PatchMapping("/{gid}") public Object update(@PathVariable long gid,
        @Valid @RequestBody GroupRequests.Write request,@AuthenticationPrincipal CustomUserDetails user) {
        groups.update(gid,request,user); return ApiResponse.success(null);
    }
    @PatchMapping("/{gid}/members/{uid}") public Object changeRole(@PathVariable long gid,@PathVariable long uid,
        @Valid @RequestBody GroupRequests.MemberRole request,@AuthenticationPrincipal CustomUserDetails user) {
        groups.changeMemberRole(gid,uid,request.roleId(),user); return ApiResponse.success(null);
    }
    @DeleteMapping("/{gid}/members/{uid}") public Object removeMember(@PathVariable long gid,@PathVariable long uid,
        @AuthenticationPrincipal CustomUserDetails user) {
        groups.removeMember(gid,uid,user); return ApiResponse.success(null);
    }
    @GetMapping("/{gid}/roles") public Object roles(@PathVariable long gid,
        @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.roles(gid,user));
    }
    @PostMapping("/{gid}/roles") public Object createRole(@PathVariable long gid,
        @Valid @RequestBody GroupRequests.RoleWrite request,@AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(groups.createRole(gid,request,user));
    }
    @PutMapping("/{gid}/roles/{rid}") public Object updateRole(@PathVariable long gid,@PathVariable long rid,
        @Valid @RequestBody GroupRequests.RoleWrite request,@AuthenticationPrincipal CustomUserDetails user) {
        groups.updateRole(gid,rid,request,user); return ApiResponse.success(null);
    }
    @DeleteMapping("/{gid}/roles/{rid}") public Object deleteRole(@PathVariable long gid,@PathVariable long rid,
        @AuthenticationPrincipal CustomUserDetails user) {
        groups.deleteRole(gid,rid,user); return ApiResponse.success(null);
    }
}
