package com.drewdrew0414.global.openai;

/**
 * 나중에 승인된 서버 작업에만 연결할 내부 경계입니다. 웹용 DTO·프록시가 아닙니다.
 * 임의 URL·모델·키를 인자로 받지 않으며 현재 구현은 항상 사용 불가 예외를 반환합니다.
 */
public interface OpenAiGateway {
    String generateForApprovedServerTask(String input);
}
