package com.drewdrew0414.domain.user.entity;

/*
 * password_reset 테이블과 매핑되는 엔티티입니다.
 * 비밀번호를 잊어버린 유저가 이메일 인증 후 비밀번호를 재설정할 수 있도록 1회용 토큰을 발급/관리합니다.
 *
 * [테이블 컬럼 → 필드]
 * id           BIGINT PK                -> Long id
 * user_id      BIGINT NOT NULL          -> Long userId
 * reset_token  VARCHAR(255) NOT NULL UNIQUE -> String resetToken
 * is_used      BOOLEAN NOT NULL DEFAULT FALSE -> boolean used
 * expires_at   DATETIME NOT NULL        -> LocalDateTime expiresAt
 * created_at   DATETIME                 -> LocalDateTime createdAt
 */

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// JPA가 이 클래스를 DB 테이블과 매핑되는 엔티티로 인식하게 한다.

@Table(name = "password_reset")
// 매핑할 실제 테이블 이름 지정

@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)

@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA용 기본 생성자, 외부에서 new PasswordReset()으로 막 만들지 못하게 protected로 제한

public class PasswordReset {
    @Id
    // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // DB의 AUTO_INCREMENT에 값 생성을 위임
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "reset_token", nullable = false, unique = true, length = 255)
    private String resetToken;
    // UUID.randomUUID() 등으로 생성

    @Column(name = "is_used", nullable = false)
    private boolean used = false;
    // 1회용 토큰으로, 쓰고 나면 반드시 true로 변경

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public PasswordReset(Long userId, String resetToken, LocalDateTime expiresAt) {
        this.userId = userId;
        this.resetToken = resetToken;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    // DB에 INSERT 되기 직전에 자동 호출 됨
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void markAsUsed() {
        // 비밀번호 변경(confirmReset)에 성공했을 때 호출되는 로직, 이후 같은 토큰 재사용을 막음
        this.used = true;
    }

    public boolean isValid() {
        // 아직 사용되지 않았고, 유효 시간(30분)도 지나지 않았을 때만 true
        return !this.used && this.expiresAt.isAfter(LocalDateTime.now());
    }

}
