package com.drewdrew0414.global.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** React의 알려진 화면 주소만 전달합니다. API·파일 경로는 SPA로 삼키지 않습니다. */
@Controller
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SpaController {
    public static final String[] PUBLIC_ROUTES = {
        "/", "/app.html", "/login", "/signup", "/password-reset", "/oauth/callback",
        "/problems", "/problems/new", "/problems/mine", "/problems/{id:[0-9]+}", "/problems/{id:[0-9]+}/edit",
        "/steps", "/ranking", "/users", "/users/{username}", "/me", "/me/edit", "/dashboard",
        "/submissions", "/submissions/{id:[0-9]+}", "/create", "/admin", "/admin/review",
        "/admin/community", "/admin/community/{id:[0-9]+}", "/forum", "/forum/new",
        "/forum/{id:[0-9]+}", "/forum/{id:[0-9]+}/edit", "/community", "/notices",
        "/groups", "/groups/{gid:[0-9]+}", "/groups/{gid:[0-9]+}/problems/{id:[0-9]+}",
        "/groups/{gid:[0-9]+}/contests/{cid:[0-9]+}", "/contests", "/contests/{id:[0-9]+}"
    };

    @GetMapping({"/", "/app.html", "/login", "/signup", "/password-reset", "/oauth/callback",
        "/problems", "/problems/new", "/problems/mine", "/problems/{id:[0-9]+}", "/problems/{id:[0-9]+}/edit",
        "/steps", "/ranking", "/users", "/users/{username}", "/me", "/me/edit", "/dashboard",
        "/submissions", "/submissions/{id:[0-9]+}", "/create", "/admin", "/admin/review",
        "/admin/community", "/admin/community/{id:[0-9]+}", "/forum", "/forum/new",
        "/forum/{id:[0-9]+}", "/forum/{id:[0-9]+}/edit", "/community", "/notices",
        "/groups", "/groups/{gid:[0-9]+}", "/groups/{gid:[0-9]+}/problems/{id:[0-9]+}",
        "/groups/{gid:[0-9]+}/contests/{cid:[0-9]+}", "/contests", "/contests/{id:[0-9]+}"})
    public String page(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return "forward:/ui/index.html";
    }
}
