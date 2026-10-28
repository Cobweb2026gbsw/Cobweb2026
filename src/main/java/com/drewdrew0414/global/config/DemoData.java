package com.drewdrew0414.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** 명시적으로 데모를 켠 새 DB에만 1000번과 비공개 케이스를 넣습니다. 기존 문제를 덮어쓰지 않습니다. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="demo.seed",havingValue="true")
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate db;
    @Value("${curriculum.level0.enabled:true}") private boolean curriculumEnabled;
    @Override @Transactional public void run(ApplicationArguments arguments) {
        if(curriculumEnabled) return;
        // 이전에 보관한 데모가 있으면 1000번에 다시 생성하지 않습니다.
        if(db.queryForObject("""
            SELECT COUNT(*) FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_title='체인 레이저' AND u.username='cobweb-system'
            """,Long.class)>0) return;
        if(db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number=1000",Long.class)>0) return;
        // SQL 원본은 단일 PostgreSQL 베이스라인의 함수에만 두며 전체 스키마를 재실행하지 않습니다.
        db.queryForObject("SELECT cobweb_seed_chain_laser(?)",Long.class,1000);
    }
}
