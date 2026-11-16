package com.drewdrew0414;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostgreSQL은 문장 오류 후 트랜잭션이 중단되므로, 고의 제약 위반 검사는 저장점으로 격리합니다. */
public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    public static DataAccessException rejected(JdbcTemplate db, Runnable action) {
        return db.execute((ConnectionCallback<DataAccessException>) connection -> {
            var savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
            try {
                var failure=assertThrows(DataAccessException.class, action::run);
                var sql=assertInstanceOf(java.sql.SQLException.class,failure.getMostSpecificCause());
                // 단순 문법 오류로도 제약 검사가 통과하는 거짓 양성을 막습니다.
                assertTrue(sql.getSQLState()!=null && (sql.getSQLState().startsWith("23") || sql.getSQLState().equals("42804")),
                    "expected integrity or native column-type rejection, not arbitrary SQL failure");
                return failure;
            } finally {
                if (savepoint != null) {
                    connection.rollback(savepoint);
                    connection.releaseSavepoint(savepoint);
                }
            }
        });
    }
}
