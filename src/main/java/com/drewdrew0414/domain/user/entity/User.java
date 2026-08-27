package com.drewdrew0414.domain.user.entity;

/*
 * users 테이블과 매핑되는 회원 엔티티입니다.
 * 사이트에 가입한 유저 한 명의 정보(계정, 권한, 상태, 랭킹 통계)를 하나의 객체로 표현합니다.
 *
 * 로그인 기능에서는 이 엔티티가 "인증의 대상"이 됩니다. 로그인 시 username으로 이 엔티티를 찾아와서
 * password를 검증하고, 성공하면 이 안의 role/status 값을 기반으로 토큰을 발급하게 됩니다.
 *
 * [테이블 컬럼 → 필드]
 * id                    BIGINT PK AUTO_INCREMENT     -> Long id
 * username              VARCHAR(16) UNIQUE NOT NULL  -> String username
 * email                 VARCHAR(255) NULL            -> String email
 * provider              ENUM(GOOGLE,GITHUB,NAVER)    -> Provider provider (소셜 로그인 유저만 값이 있음)
 * provider_id           VARCHAR(255)                 -> String providerId
 * password              VARCHAR(255) NOT NULL        -> String password (BCrypt로 해시된 값만 저장)
 * bio                   VARCHAR(300) DEFAULT ''      -> String bio
 * github_url            VARCHAR(255) DEFAULT ''      -> String githubUrl
 * role                  ENUM(...) DEFAULT USER       -> Role role (권한)
 * status                ENUM(...) DEFAULT ACTIVE     -> UserStatus status (계정 상태 — 로그인 가능 여부를 좌우)
 * solved_count          INT DEFAULT 0                -> int solvedCount
 * submit_count          INT DEFAULT 0                -> int submitCount
 * rank_type             ENUM(...) DEFAULT INITIATE   -> RankType rankType
 * rank_int              TINYINT DEFAULT 5            -> int rankInt
 * login_failed_count    INT DEFAULT 0                -> int loginFailedCount (연속 로그인 실패 횟수)
 * last_login_at         DATETIME                     -> LocalDateTime lastLoginAt
 * created_at            TIMESTAMP DEFAULT NOW        -> LocalDateTime createdAt
 * updated_password_at   TIMESTAMP DEFAULT NOW        -> LocalDateTime updatedPasswordAt
 */

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// JPA가 이 클래스를 DB 테이블과 매핑되는 엔티티로 인식하게 한다.

@Table(name = "users")
// 매핑할 실제 테이블 이름 지정

@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)

@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA용 기본 생성자, 외부에서 new User()로 막 만들지 못하게 protected로 제한

public class User {
    @Id
    // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    // DB의 AUTO_INCREMENT에 값 생성을 위임
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    //     ^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^  ^^^^^^^^^^^^
    //        NULL 값 설정      UNIQUE 설정     길이 설정

    private String username;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    // enum을 이름 그대로 문자열로 저장
    // ORDINAL 사용 시, 순서 바뀔 때 데이터가 꼬임

    @Column(length = 20)
    private Provider provider;

    @Column(name = "provider_id", length = 255)
    private String providerId;

    @Column(nullable = false, length = 255)
    private String password;
    // BCrypt로 해시된 값만 저장할 예정

    @Column(length = 300)
    private String bio = "";
    // 필드 기본값 — DB 컬럼 DEFAULT ''와 맞춤

    @Column(name = "github_url", length = 255)
    private String githubUrl = "";

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Role role = Role.USER;
    // 기본 권한을 USER로 설정

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "solved_count")
    private int solvedCount = 0;

    @Column(name = "submit_count")
    private int submitCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "rank_type", length = 20)
    private RankType rankType = RankType.INITIATE;

    @Column(name = "rank_int")
    private int rankInt = 5;

    @Column(name = "login_failed_count")
    private int loginFailedCount = 0;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", updatable = false)
    //                           ^^^^^^^^^^^^^^^^^^
    //                              수정 불가 표시
    private LocalDateTime createdAt;

    @Column(name = "updated_password_at")
    private LocalDateTime updatedPasswordAt;

    @Builder
    // 회원가입처럼 새로 만들 때 필요한 값만 받는 생성자에 빌더를 붙임
    // id, role, status 등은 여기서 안 받고 기본값 그대로 둠
    public User(String username, String email, Provider provider, String providerId, String password, String bio, String githubUrl) {
        this.username = username;
        this.email = email;
        this.provider = provider;
        this.providerId = providerId;
        this.password = password;
        if (bio != null) this.bio = bio;                   // null이면 필드 기본값("") 그대로 유지
        if (githubUrl != null) this.githubUrl = githubUrl; // null이면 필드 기본값("") 그대로 유지
    }

    @PrePersist
    // DB에 INSERT 되기 직전에 자동 호출 됨
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedPasswordAt = LocalDateTime.now();
    }

    public void changePassword(String encodedPassword) {
        // 비밀번호 바꿨을 때 호출되는 로직
        this.password = encodedPassword;
        this.updatedPasswordAt = LocalDateTime.now();
    }

    public void increaseLoginFailedCount() {
        // 로그인 실패 시 호출되는 로직
        this.loginFailedCount++;
    }

    public void resetLoginFailedCount() {
        // 로그인 실패 횟수를 초기화하는 로직
        this.loginFailedCount = 0;
    }

    public void recordLogin() {
        // 마지막 로그인 일자를 현재로 저장하는 로직
        this.lastLoginAt = LocalDateTime.now();
    }

    public boolean isLoginable() {
        // 로그인이 가능한가?에 대한 참/거짓을 반환하는 로직
        return this.status == UserStatus.ACTIVE;
    }
}
