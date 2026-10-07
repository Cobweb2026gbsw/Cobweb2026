package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * PasswordReset 엔티티(비밀번호 재설정용 1회용 토큰)에 대한 데이터 접근을 담당합니다.
 */
// JpaRepository<PasswordReset, Long> -> 기본 CRUD는 자동으로 생김
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    // 사용자 행을 먼저 잠그기 위한 ID 조회입니다. 이후 토큰 행을 잠가 최신 사용 상태를 다시 확인합니다.
    @org.springframework.data.jpa.repository.Query("select p.userId from PasswordReset p where p.resetToken = :resetToken")
    Optional<Long> findUserIdByResetToken(@org.springframework.data.repository.query.Param("resetToken") String resetToken);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    Optional<PasswordReset> findByResetToken(String resetToken); // 비밀번호 확정 변경 시 토큰으로 조회

    // 같은 계정에 앞서 발급된 모든 재설정 토큰을 한 번의 변경으로 무효화합니다.
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update PasswordReset p set p.used = true where p.userId = :userId and p.used = false")
    int invalidateAllByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);
}
