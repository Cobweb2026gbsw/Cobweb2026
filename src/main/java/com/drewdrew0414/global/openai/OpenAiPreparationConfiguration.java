package com.drewdrew0414.global.openai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 용도가 지정되지 않은 레거시 게이트웨이는 계속 닫혀 있습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiPreparationConfiguration {
    @Bean public OpenAiGateway openAiGateway(OpenAiProperties properties) {
        return new PreparedOpenAiGateway(properties);
    }
}
