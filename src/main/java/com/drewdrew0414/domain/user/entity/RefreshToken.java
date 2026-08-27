package com.drewdrew0414.domain.user.entity;

/*
 * refresh_token 테이블과 매핑되는 엔티티입니다.
 * JWT 방식 로그인에서는 access token이 짧게 만료되기 때문에, 매번 재로그인하지 않고도
 * 새 access token을 다시 받을 수 있게 해주는 refresh token을 DB에 저장해서 관리합니다.
 * 로그아웃하거나 토큰 탈취가 의심될 때 이 레코드를 지우면 해당 refresh token은 더 이상 쓸 수 없게 됩니다.
 *
 * [테이블 컬럼 → 필드]
 * id          BIGINT PK                  -> Long id
 * user_id     BIGINT NOT NULL            -> Long userId
 * token       VARCHAR(512) NOT NULL UNIQUE -> String token
 * expires_at  DATETIME NOT NULL          -> LocalDateTime expiresAt
 * created_at  DATETIME NOT NULL          -> LocalDateTime createdAt
 */

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// JPA가 이 클래스를 DB 테이블과 매핑되는 엔티티로 인식하게 한다.

@Table(name = "refresh_token")
// 매핑할 실제 테이블 이름 지정

@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)

@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA용 기본 생성자, 외부에서 new RefreshToken()으로 막 만들지 못하게 protected로 제한

public class RefreshToken {
    @Id
    // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // DB의 AUTO_INCREMENT에 값 생성을 위임
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, unique = true, length = 512)
    // JWT 문자열 자체를 그대로 저장 (재발급 요청 시 이 값으로 레코드를 찾음)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    //                                              ^^^^^^^^^^^^^^^^^^
    //                                                 수정 불가 표시
    private LocalDateTime createdAt;

    public RefreshToken(Long userId, String token, LocalDateTime expiresAt) {
        this.userId = userId;
        this.token = token;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    // DB에 INSERT 되기 직전에 자동 호출 됨
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        // 재발급/로그아웃 로직에서 DB에 남아있는 토큰이 아직 유효한지 판단할 때 씀
        return this.expiresAt.isBefore(LocalDateTime.now());
    }

}
