package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.Provider;
import com.drewdrew0414.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * User 엔티티에 대한 데이터 접근을 담당합니다.
 * 로그인 시 아이디로 유저를 찾거나, 회원가입 시 아이디/이메일 중복 여부를 확인하는 등
 * DB의 users 테이블을 조회/저장하는 창구 역할을 합니다.
 */
// JpaRepository<User, Long> -> User 엔티티, PK 타입 Long을 지정하면 save/findById/delete 등 기본 CRUD가 자동으로 생김
// 아래 메서드들은 이름 규칙(findBy.../existsBy...)만 맞춰 선언하면 Spring Data JPA가 쿼리를 자동으로 만들어줌
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username); // 로그인 시 아이디로 유저 조회
    Optional<User> findByEmail(String email); // 비밀번호 재설정 등 이메일로 유저 조회
    boolean existsByUsername(String username); // 회원가입 시 아이디 중복 체크
    boolean existsByEmail(String email); // 회원가입 시 이메일 중복 체크
    Optional<User> findByProviderAndProviderId(Provider provider, String providerId); // 소셜 로그인 계정 조회

}
