package com.drewdrew0414.domain.user.entity;

/*
 * users_log 테이블과 매핑되는 로그인 시도 기록 엔티티입니다.
 * 로그인 성공/실패와 상관없이 "누가, 언제, 어디서, 어떤 이유로" 로그인을 시도했는지를 남기는 감사(audit) 로그입니다.
 * 나중에 무차별 대입 공격(brute-force) 탐지나 이상 로그인 알림 같은 기능을 만들 때 이 데이터를 근거로 쓰게 됩니다.
 *
 * [테이블 컬럼 → 필드]
 * id              BIGINT PK                    -> Long id
 * user_id         BIGINT NULL                  -> Long userId (유저를 찾은 경우에만 값이 채워짐)
 * attempt_email   VARCHAR(255) NULL            -> String attemptEmail (이메일으로 시도 시)
 * attempt_name    VARCHAR(16) NULL             -> String attemptName (아이디로 시도 시)
 * status          ENUM(...) NOT NULL           -> LoginStatus status (시도 결과)
 * log_created_at  TIMESTAMP                    -> LocalDateTime logCreatedAt
 * client_ip       VARCHAR(45) NOT NULL         -> String clientIp
 * user_agent      VARCHAR(255)                 -> String userAgent
 */

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// JPA가 이 클래스를 DB 테이블과 매핑되는 엔티티로 인식하게 한다.

@Table(name = "users_log")
// 매핑할 실제 테이블 이름 지정

@Getter
// 모든 필드의 getter를 자동 생성 (Lombok)

@NoArgsConstructor(access = AccessLevel.PROTECTED)
// JPA용 기본 생성자, 외부에서 new UserLog()로 막 만들지 못하게 protected로 제한
// 실제 생성은 아래 success/failPasswordMismatch/failUserNotFound/bannedUser 정적 팩토리 메서드로만 하게 강제함

public class UserLog {
    @Id
    // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // DB의 AUTO_INCREMENT에 값 생성을 위임
    private Long id;

    @Column(name = "user_id")
    // 유저를 못 찾은 채로 실패한 경우(FAIL_USER_NOT_FOUND)엔 null로 남음
    private Long userId;

    @Column(name = "attempt_email", length = 255)
    private String attemptEmail;

    @Column(name = "attempt_name", length = 16)
    private String attemptName;

    @Enumerated(EnumType.STRING)
    // enum을 이름 그대로 문자열로 저장 (ORDINAL 쓰면 순서 바뀔 때 데이터가 꼬임)
    @Column(nullable = false, length = 30)
    private LoginStatus status;

    @Column(name = "log_created_at", updatable = false)
    //                                ^^^^^^^^^^^^^^^^^^
    //                                   수정 불가 표시, 로그는 한 번 남으면 고칠 일이 없음
    private LocalDateTime logCreatedAt;

    @Column(name = "client_ip", nullable = false, length = 45)
    // IPv6까지 담을 수 있게 45자
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    private UserLog(Long userId, String attemptName, String attemptEmail, LoginStatus status, String clientIp, String userAgent) {
        this.userId = userId;
        this.attemptName = attemptName;
        this.attemptEmail = attemptEmail;
        this.status = status;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
    }

    @PrePersist
    // DB에 INSERT 되기 직전에 자동 호출 됨
    protected void onCreate() {
        this.logCreatedAt = LocalDateTime.now();
    }

    // 아래 네 개는 AuthService가 로그인 시도 결과에 따라 골라 쓰는 정적 팩토리 메서드들.
    // status를 직접 넘기게 하지 않고 메서드 이름으로 의도를 드러내서, 호출부에서 실수로 잘못된 status를 넣는 걸 막는다.

    public static UserLog success(Long userId, String attemptName, String attemptEmail, String clientIp, String userAgent) {
        return new UserLog(userId, attemptName, attemptEmail, LoginStatus.SUCCESS, clientIp, userAgent);
    }

    public static UserLog failPasswordMismatch(Long userId, String attemptName, String attemptEmail, String clientIp, String userAgent) {
        return new UserLog(userId, attemptName, attemptEmail, LoginStatus.FAIL_PASSWORD_MISMATCH ,clientIp, userAgent);
    }

    public static UserLog failUserNotFound(String attemptName, String attemptEmail, String clientIp, String userAgent) {
        // userId를 null로 고정 -> 애초에 유저를 못 찾았으니 연결할 대상이 없음
        return new UserLog(null, attemptName, attemptEmail, LoginStatus.FAIL_USER_NOT_FOUND, clientIp, userAgent);
    }

    public static UserLog bannedUser(Long userId, String attemptName, String attemptEmail, String clientIp, String userAgent) {
        return new UserLog(userId, attemptName, attemptEmail, LoginStatus.BANNED_USER, clientIp, userAgent);
    }
}
