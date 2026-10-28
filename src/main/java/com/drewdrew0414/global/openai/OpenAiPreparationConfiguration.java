package com.drewdrew0414.global.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 기본 비활성입니다. 활성 플래그만 바뀌어도 실제 클라이언트가 생성되지 않습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiPreparationConfiguration {
    @Bean public OpenAiGateway openAiGateway(OpenAiProperties properties) {
        return new PreparedOpenAiGateway(properties);
    }
}
