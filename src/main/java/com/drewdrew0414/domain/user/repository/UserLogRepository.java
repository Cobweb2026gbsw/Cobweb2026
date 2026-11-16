package com.drewdrew0414.domain.user.repository;

import com.drewdrew0414.domain.user.entity.UserLog;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * UserLog 엔티티(로그인 시도 기록)에 대한 데이터 접근을 담당합니다.
 * 로그인 시도가 있을 때마다 기록을 저장하고, 필요하면 특정 IP의 최근 실패 횟수를 조회하는 용도로도 쓰입니다.
 */
// JpaRepository<UserLog, Long> -> 지금은 save()만 쓰고 있지만, 나중에 client_ip/log_created_at 인덱스를 활용한
// 조회 메서드(예: 최근 5분간 실패 횟수)를 추가할 때도 이 인터페이스에 선언만 하면 됨
public interface UserLogRepository extends JpaRepository<UserLog, Long> {

}
