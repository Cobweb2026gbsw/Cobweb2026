package com.drewdrew0414.global.config;

import com.drewdrew0414.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

/** ApplicationRunner가 완료된 뒤에만 런처가 웹 준비 완료로 판단합니다. */
@RestController @RequiredArgsConstructor
public class ReadinessController {
    private final ApplicationAvailability availability;

    @GetMapping("/api/ready")
    public ResponseEntity<ApiResponse<Map<String,Boolean>>> ready() {
        if(availability.getReadinessState()==ReadinessState.ACCEPTING_TRAFFIC)
            return ResponseEntity.ok(ApiResponse.success(Map.of("ready",true)));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error("SERVICE_NOT_READY","서비스 준비 중입니다."));
    }
}
