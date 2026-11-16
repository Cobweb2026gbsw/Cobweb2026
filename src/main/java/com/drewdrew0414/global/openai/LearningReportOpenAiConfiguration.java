package com.drewdrew0414.global.openai;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class LearningReportOpenAiConfiguration {
    @Bean(destroyMethod = "close")
    ResponsesLearningReportGateway learningReportGateway(OpenAiProperties properties, ObjectMapper json) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new ResponsesLearningReportGateway(properties, json, client);
    }
}
