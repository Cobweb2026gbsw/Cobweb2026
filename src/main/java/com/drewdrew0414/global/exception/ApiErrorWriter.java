package com.drewdrew0414.global.exception;

import com.drewdrew0414.global.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.ObjectMapper;

/** MVC Advice 밖의 인증 필터 실패도 동일 JSON으로 반환합니다. 예외 원문을 인자로 넘기지 않습니다. */
public final class ApiErrorWriter {
    private static final ObjectMapper JSON=new ObjectMapper();
    private ApiErrorWriter() {}
    public static void write(HttpServletResponse response,int status,String errorCode,String message) throws IOException {
        if(response.isCommitted())return;
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control","no-store");
        response.getWriter().write(JSON.writeValueAsString(ApiResponse.error(errorCode,message)));
    }
}
