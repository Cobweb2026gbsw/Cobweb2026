package com.drewdrew0414.domain.submission;

import com.drewdrew0414.global.common.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Arrays;

/** 화면은 서버의 허용 목록을 사용합니다. 실행 명령/이미지 경로는 외부에 전달하지 않습니다. */
@RestController
public class LanguageController {
    @GetMapping("/api/languages")
    public Object list() {
        return ApiResponse.success(Arrays.stream(Language.values()).map(Language::description).toList());
    }
}
