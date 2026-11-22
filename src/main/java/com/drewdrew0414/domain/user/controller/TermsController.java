package com.drewdrew0414.domain.user.controller;

import com.drewdrew0414.domain.user.service.TermsPublicationService;
import com.drewdrew0414.global.common.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TermsController {
    private final TermsPublicationService terms;

    public TermsController(TermsPublicationService terms) {
        this.terms = terms;
    }

    @GetMapping("/api/terms/current")
    public ApiResponse<TermsPublicationService.Publication> current() {
        return ApiResponse.success(terms.current());
    }
}
