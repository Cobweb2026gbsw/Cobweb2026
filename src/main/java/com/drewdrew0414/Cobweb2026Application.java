package com.drewdrew0414;

import com.drewdrew0414.global.config.LocalMigrationMode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// 이 애노테이션 하나가 @Configuration + @EnableAutoConfiguration + @ComponentScan을 합쳐놓은 것
// 이 클래스가 있는 패키지(com.drewdrew0414) 아래를 전부 컴포넌트 스캔 대상으로 삼는다.
public class Cobweb2026Application {

    public static void main(String[] args) {
        if (LocalMigrationMode.requested(args)) {
            LocalMigrationMode.validateArguments(args);
            var migration = new SpringApplication(Cobweb2026Application.class);
            migration.addInitializers(context -> LocalMigrationMode.validateEnvironment(context.getEnvironment()));
            // Flyway와 컨텍스트 초기화가 끝난 뒤 scheduler·DB 풀까지 닫아 단발 JVM을 정상 종료합니다.
            // 일반 웹/워커 기동에는 이 종료 경로를 적용하지 않습니다.
            try (var context = migration.run(args)) {
                // 마이그레이션 실패는 run()의 예외로 전파되어 launcher가 실패 복구합니다.
            }
            return;
        }
        // 내장 톰캣을 띄우고 스프링 컨테이너(ApplicationContext)를 생성/초기화하는 진입점
        SpringApplication.run(Cobweb2026Application.class, args);
    }

}
