package com.drewdrew0414;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** 단일 PostgreSQL 원본과 실제 Flyway 적용 결과가 일치하는지 전용 DB에서 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PostgresqlSchemaIntegrationTest {
    @Autowired JdbcTemplate db;

    @Test void onlyOneSqlResourceIsPackagedAndItIncludesTheOptionalSeedDefinition() throws Exception {
        var resolver=new PathMatchingResourcePatternResolver();
        var migrations=resolver.getResources("classpath*:db/migration/*.sql");
        assertEquals(1,migrations.length,"stale copied migrations must not be packaged");
        assertEquals("V1__postgresql_schema.sql",migrations[0].getFilename());
        assertEquals(0,resolver.getResources("classpath*:demo/*.sql").length);
        String sql=migrations[0].getContentAsString(StandardCharsets.UTF_8);
        assertTrue(sql.contains("CREATE FUNCTION cobweb_seed_chain_laser"));
        for(String unsupported:new String[]{"AUTO_INCREMENT","ENGINE=InnoDB","INSERT IGNORE","ON DUPLICATE KEY","DELIMITER $$"})
            assertFalse(sql.contains(unsupported),unsupported);
    }

    @Test void postgres18HasOneSuccessfulBaselineAndAll35ApplicationTables() {
        assertTrue(Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class))>=180000);
        assertTrue(db.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Integer.class));
        assertEquals(35,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",Integer.class));
    }

    @Test void jsonbBooleanPrecisionAndDatabaseGuardsAreNativePostgresTypes() {
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND data_type='jsonb'",Integer.class));
        assertEquals("boolean",db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='problems' AND column_name='author_public'",String.class));
        assertEquals(8,db.queryForObject("SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND NOT t.tgisinternal",Integer.class));
        assertEquals(6,db.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='submissions' AND column_name='submitted_at'",Integer.class));
    }
}
