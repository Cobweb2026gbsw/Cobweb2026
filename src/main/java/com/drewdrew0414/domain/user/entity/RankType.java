package com.drewdrew0414.domain.user.entity;

/**
 * 문제 풀이 실적(solved_count)에 따라 매겨지는 랭크 등급입니다.
 * 로그인 기능과는 직접 관련 없고, 마이페이지나 랭킹 화면에서 유저 실력을 보여주는 용도로 쓰입니다.
 * 낮은 순서대로 INITIATE(입문), SKILLED(숙련), ELITE(정예), EXPERT(달인), LEGEND(전설), MYTHIC(신화), ABSOLUTE(신성)
 */
public enum RankType {
    INITIATE, // 입문
    SKILLED,  // 숙련
    ELITE,    // 정예
    EXPERT,   // 달인
    LEGEND,   // 전설
    MYTHIC,   // 신화
    ABSOLUTE  // 신성
}
