package com.drewdrew0414;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL 기준 스키마, 변경 마이그레이션과 실제 Flyway 적용 결과를 전용 DB에서 확인합니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class PostgresqlSchemaIntegrationTest {
    @Autowired JdbcTemplate db;

    @Test void onlyExpectedPostgresMigrationsArePackagedAndBaselineIncludesTheOptionalSeed() throws Exception {
        var resolver=new PathMatchingResourcePatternResolver();
        var migrations=resolver.getResources("classpath*:db/migration/*.sql");
        assertEquals(List.of("V1__postgresql_schema.sql","V2__learning_reports.sql","V3__rank_points.sql"),
                Arrays.stream(migrations).map(org.springframework.core.io.Resource::getFilename).sorted().toList(),
                "stale copied migrations must not be packaged");
        assertEquals(0,resolver.getResources("classpath*:demo/*.sql").length);
        for(var migration:migrations) {
            String sql=migration.getContentAsString(StandardCharsets.UTF_8);
            if("V1__postgresql_schema.sql".equals(migration.getFilename()))
                assertTrue(sql.contains("CREATE FUNCTION cobweb_seed_chain_laser"));
            for(String unsupported:new String[]{"AUTO_INCREMENT","ENGINE=InnoDB","INSERT IGNORE","ON DUPLICATE KEY","DELIMITER $$"})
                assertFalse(sql.contains(unsupported),unsupported);
        }
    }

    @Test void bothManualInstallersMatchTheRankMigrationAppliedByFlyway() throws Exception {
        String script="V3__rank_points.sql";
        var resource=new org.springframework.core.io.ClassPathResource("db/migration/"+script);
        String block="-- BEGIN "+script+"\n"+resource.getContentAsString(StandardCharsets.UTF_8)
            +"-- END "+script;
        int checksum=db.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='3' AND success",Integer.class);
        for(String file:List.of("neon-bootstrap.sql","upgrade-v3-rank-points.sql")) {
            String installer=java.nio.file.Files.readString(java.nio.file.Path.of("deploy/postgres",file));
            assertTrue(installer.contains(block),file);
            assertTrue(installer.contains("'"+script+"', "+checksum+", current_user"),file);
        }
    }

    @Test void postgres18HasAllThreeMigrationsAndAll39ApplicationTables() {
        assertTrue(Integer.parseInt(db.queryForObject("SHOW server_version_num",String.class))>=180000);
        assertTrue(db.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success",Integer.class));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history",Integer.class));
        assertEquals(39,db.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=current_schema() AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history'",Integer.class));
    }

    @Test void jsonbBooleanPrecisionAndDatabaseGuardsAreNativePostgresTypes() {
        assertEquals(7,db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=current_schema() AND data_type='jsonb'",Integer.class));
        assertEquals("text",db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='learning_reports' AND column_name='carry_forward_summary'",String.class));
        assertEquals("boolean",db.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='problems' AND column_name='author_public'",String.class));
        assertEquals(11,db.queryForObject("SELECT COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=current_schema() AND NOT t.tgisinternal",Integer.class));
        assertEquals(6,db.queryForObject("SELECT datetime_precision FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='submissions' AND column_name='submitted_at'",Integer.class));
    }
}
