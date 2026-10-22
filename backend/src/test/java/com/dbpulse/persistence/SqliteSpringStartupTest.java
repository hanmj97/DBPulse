package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in SQLite storage must boot the real Spring context without PostgreSQL
 * or Docker. Uses a fresh disposable test file and never touches user data.
 */
@SpringBootTest(properties = {
    "spring.profiles.active=sqlite-preview",
    "dbpulse.mssql.enabled=false",
    "dbpulse.local-targets.enabled=true",
    "spring.main.web-application-type=none"
})
class SqliteSpringStartupTest {
    private static final Path SQLITE_FILE = createTempSqlitePath();

    private static Path createTempSqlitePath() {
        try {
            Path path = Files.createTempFile("dbpulse-sqlite-spring-", ".sqlite");
            Files.delete(path);
            path.toFile().deleteOnExit();
            return path;
        } catch (IOException failure) {
            throw new IllegalStateException("SQLITE_TEST_FILE_ERROR");
        }
    }

    @DynamicPropertySource
    static void metadataPath(DynamicPropertyRegistry props) {
        props.add("DBPULSE_SQLITE_PREVIEW_PATH", SQLITE_FILE::toString);
    }

    @Autowired JdbcTemplate jdbc;

    @Test void startsWithoutPostgresAndEnablesForeignKeyConstraints() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM local_target_registry", Integer.class);
        assertEquals(0,count);
        Integer fkEnabled=jdbc.queryForObject("PRAGMA foreign_keys",Integer.class);
        assertEquals(1,fkEnabled);
        assertTrue(Files.isRegularFile(SQLITE_FILE));
    }
}
