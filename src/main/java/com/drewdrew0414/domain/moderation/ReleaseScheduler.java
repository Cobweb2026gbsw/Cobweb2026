package com.drewdrew0414.domain.moderation;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 잠들었던 웹 컨테이너도 첫 요청을 받기 전에 지난 예약을 처리합니다. */
@Component @RequiredArgsConstructor
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class ReleaseScheduler implements ApplicationRunner {
    private final ProblemReleaseService releases;
    @Override public void run(ApplicationArguments ignored) { process(); }
    @Scheduled(fixedDelay=5000,initialDelay=5000)
    public void process() {
        while (releases.runNextDue()) { /* 각 묶음의 트랜잭션을 독립적으로 커밋합니다. */ }
    }
}
