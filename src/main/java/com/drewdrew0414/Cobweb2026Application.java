package com.drewdrew0414;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// 이 애노테이션 하나가 @Configuration + @EnableAutoConfiguration + @ComponentScan을 합쳐놓은 것
// 이 클래스가 있는 패키지(com.drewdrew0414) 아래를 전부 컴포넌트 스캔 대상으로 삼는다.
public class Cobweb2026Application {

    public static void main(String[] args) {
        // 내장 톰캣을 띄우고 스프링 컨테이너(ApplicationContext)를 생성/초기화하는 진입점
        SpringApplication.run(Cobweb2026Application.class, args);
    }

}
