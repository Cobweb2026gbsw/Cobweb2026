package com.drewdrew0414.domain.user.entity;

/*
 * email_verifications 테이블과 매핑되는 엔티티입니다.
 * 회원가입이나 비밀번호 재설정 시 입력한 이메일이 실제 본인 소유인지 확인하기 위한 인증 코드를 관리합니다.
 * 로그인 기능 자체보다는 회원가입/비밀번호 찾기 흐름에서 쓰이지만, 같은 인증 도메인이라 함께 둡니다.
 *
 * [테이블 컬럼 → 필드]
 * id            BIGINT PK                  -> Long id
 * email         VARCHAR(255) NOT NULL      -> String email
 * code          CHAR(8) NOT NULL           -> String code
 * purpose       ENUM(JOIN, PASSWORD_RESET) -> VerificationPurpose purpose
 * is_verified   BOOLEAN NOT NULL DEFAULT FALSE -> boolean verified
 * expires_at    DATETIME NOT NULL          -> LocalDateTime expiresAt
 * created_at    DATETIME                   -> LocalDateTime createdAt
 */

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// JPA가 이 클래스를 DB 테이블과 매핑되는 엔티티로 인식하게 한다.

@Table(name = "email_verifications")
// 매핑할 실제 테이블 이름 지정

@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)

@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA용 기본 생성자, 외부에서 new EmailVerification()으로 막 만들지 못하게 protected로 제한
// 실제 생성은 아래 public 생성자(EmailVerificationService에서 호출)로만 함

public class EmailVerification {
    @Id
    // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // DB의 AUTO_INCREMENT에 값 생성을 위임
    private Long id;

    @Column(nullable = false, length = 255)
    private String email;

    @Column(nullable = false, length = 8)
    // 8자리 숫자 코드, table.sql의 CHAR(8)과 길이를 맞춤
    private String code;

    @Enumerated(EnumType.STRING)
    // enum을 이름 그대로 문자열로 저장 (ORDINAL 쓰면 순서 바뀔 때 데이터가 꼬임)
    @Column(length = 20)
    private VerificationPurpose purpose = VerificationPurpose.JOIN;

    @Column(name = "is_verified", nullable = false)
    // 코드 확인(verify)에 성공하면 true로 바뀜. 회원가입/비밀번호 재설정 진행 여부를 이 값으로 판단
    private boolean verified = false;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", updatable = false)
    //                           ^^^^^^^^^^^^^^^^^^
    //                              수정 불가 표시
    private LocalDateTime createdAt;

    public EmailVerification(String email, String code, VerificationPurpose purpose, LocalDateTime expiresAt) {
        this.email = email;
        this.code = code;
        this.purpose = purpose;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    // DB에 INSERT 되기 직전에 자동 호출 됨
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public void markAsVerified() {
        // 코드 검증(verifyCode)에 성공했을 때 호출되는 로직
        this.verified = true;
    }

    public boolean isExpired() {
        // 코드 검증 시 유효 시간(5분)이 지났는지 판단할 때 씀
        return this.expiresAt.isBefore(LocalDateTime.now());
    }
}
