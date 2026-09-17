package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * RefreshToken 엔티티에 대한 데이터 접근을 담당합니다.
 * 재발급 요청이 들어왔을 때 토큰이 실제 DB에 유효하게 저장돼 있는지 확인하거나,
 * 로그아웃/재로그인 시 기존 토큰을 정리하는 데 사용됩니다.
 */
// JpaRepository<RefreshToken, Long> -> 기본 CRUD(save/findById/deleteById 등)는 자동으로 생김
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token); // 재발급/로그아웃 시 토큰으로 조회
    void deleteByUserId(Long userId); // 로그아웃, 재로그인 시 기존 토큰 정리
}
