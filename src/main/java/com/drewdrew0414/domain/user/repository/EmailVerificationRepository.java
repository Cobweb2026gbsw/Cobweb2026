package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.EmailVerification;
import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.LocalDateTime;

/*
 * EmailVerification 엔티티(이메일 인증 코드)에 대한 데이터 접근을 담당합니다.
 * 같은 이메일/목적으로 코드를 여러 번 요청할 수 있으므로, 검증 시에는 항상 가장 최근에 발급된 코드를 기준으로 확인합니다.
 */
// JpaRepository<EmailVerification, Long> -> 기본 CRUD는 자동으로 생김
public interface EmailVerificationRepository extends JpaRepository<EmailVerification, Long> {
    // findTopBy...OrderByCreatedAtDesc -> email+purpose 조건에 맞는 것 중 created_at이 가장 최근인 1건만 조회
    Optional<EmailVerification> findTopByEmailAndPurposeOrderByCreatedAtDesc(String email, VerificationPurpose purpose);

    // 최근 일정 시간 안에 같은 주소/목적으로 발급한 횟수를 세어 메일 발송 요청 제한에 사용
    long countByEmailAndPurposeAndCreatedAtAfter(String email, VerificationPurpose purpose, LocalDateTime createdAfter);

    // 인증을 실제 회원가입/비밀번호 재설정에 사용한 뒤 과거 기록까지 지워 같은 인증 결과의 재사용을 막음
    void deleteAllByEmailAndPurpose(String email, VerificationPurpose purpose);
}
