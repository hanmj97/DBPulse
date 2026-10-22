package com.dbpulse.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.dbpulse.domain.QueryPerformanceSnapshot;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class QueryPerformanceHistorySqliteTest {
    @TempDir Path temp;
    @Test void savedSnapshotsSurvive15MinuteLiveWindowAreDedupedAndTargetScoped() throws Exception {
        var conf=new HikariConfig();
        conf.setJdbcUrl("jdbc:sqlite:"+temp.resolve("history.sqlite"));
        conf.setDriverClassName("org.sqlite.JDBC");
        conf.setMaximumPoolSize(1);
        conf.setMinimumIdle(1);
        conf.setConnectionTimeout(1500);
        try(var pool=new HikariDataSource(conf)){
            try(var connection=pool.getConnection()){
                ScriptUtils.executeSqlScript(connection,new ClassPathResource("schema-sqlite.sql"));
            }
            var repo=new QueryPerformanceRepository(new JdbcTemplate(pool));
            Instant older=Instant.now().minusSeconds(3600);
            Instant newer=Instant.now().minusSeconds(600);
            Instant expired=Instant.now().minusSeconds(8*24*3600);
            repo.save("target-A",List.of(
                new QueryPerformanceSnapshot(older, 17,9,1,10,3,100,older,"OBSERVE","OTHER_OR_UNKNOWN"),
                new QueryPerformanceSnapshot(newer,17,9,5,15,4,130,newer,"OBSERVE","OTHER_OR_UNKNOWN"),
                new QueryPerformanceSnapshot(newer,18,3,2,4,1,10,newer,"OBSERVE","DBPULSE_PATTERN"),
                new QueryPerformanceSnapshot(expired,19,1,1,2,1,1,expired,"OBSERVE","OTHER_OR_UNKNOWN")
            ));
            repo.save("target-B",List.of(
                new QueryPerformanceSnapshot(newer,17,9,15,50,10,100,newer,"OBSERVE","OTHER_OR_UNKNOWN")
            ));
            var archived=repo.history("target-A",20);
            assertEquals(2,archived.size());
            assertEquals(5,archived.stream().filter(x->x.queryId()==17).findFirst().orElseThrow().executionCount());
            assertTrue(repo.hasRecentPlan("target-A",17,9));
            assertFalse(repo.hasRecentPlan("target-A",19,1));
            assertFalse(repo.hasRecentPlan("target-A",17,3));
            assertFalse(repo.hasRecentPlan("target-B",18,3));
            assertEquals(1,repo.history("target-A",1).size());
        }
    }
}
