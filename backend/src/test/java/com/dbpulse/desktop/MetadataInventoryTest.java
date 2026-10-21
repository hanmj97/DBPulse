package com.dbpulse.desktop;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class MetadataInventoryTest {
    @Test void sqliteInventoryReadOnlyPreservesEveryTableAndCounts() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            ScriptUtils.executeSqlScript(db,new ClassPathResource("schema-sqlite.sql"));
            var before=MetadataInventory.counts(db);
            assertEquals(10, before.size());
            assertTrue(before.values().stream().allMatch(v->v==0L));
            db.createStatement().executeUpdate("""
                INSERT INTO local_target_registry
                (id,display_name,server_host,server_port,database_name,login_name,status)
                VALUES ('test-registry-uuid','test','localhost',1433,'DBPulseLab',
                        'readonly','REGISTERED_INACTIVE')
                """);
            var after=MetadataInventory.counts(db);
            assertEquals(1L,after.get("local_target_registry"));
            assertEquals(1L,MetadataInventory.counts(db).get("local_target_registry"));
            assertEquals(1,db.createStatement().executeQuery(
                "SELECT COUNT(*) FROM local_target_registry").getInt(1));
        }
    }
}
