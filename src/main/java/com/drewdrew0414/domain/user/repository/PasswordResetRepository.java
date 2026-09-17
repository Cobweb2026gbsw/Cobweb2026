package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * PasswordReset 엔티티(비밀번호 재설정용 1회용 토큰)에 대한 데이터 접근을 담당합니다.
 */
// JpaRepository<PasswordReset, Long> -> 기본 CRUD는 자동으로 생김
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    // 검사부터 비밀번호 변경·사용 처리까지 같은 토큰 행을 잠가 동시 재사용을 막습니다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordReset> findByResetToken(String resetToken); // 비밀번호 확정 변경 시 토큰으로 조회
}
