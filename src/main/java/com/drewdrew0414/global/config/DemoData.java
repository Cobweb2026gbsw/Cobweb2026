package com.drewdrew0414.global.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;

/** 명시적으로 데모를 켠 새 DB에만 1000번과 비공개 케이스를 넣습니다. 기존 문제를 덮어쓰지 않습니다. */
@Component @RequiredArgsConstructor
@ConditionalOnProperty(name="demo.seed",havingValue="true")
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate db;
    private final DataSource dataSource;
    @Override @Transactional public void run(ApplicationArguments arguments) {
        if(db.queryForObject("SELECT COUNT(*) FROM problems WHERE problem_number=1000",Long.class)>0) return;
        var connection=DataSourceUtils.getConnection(dataSource);
        try { ScriptUtils.executeSqlScript(connection,new ClassPathResource("demo/chain-laser.sql")); }
        finally { DataSourceUtils.releaseConnection(connection,dataSource); }
    }
}
