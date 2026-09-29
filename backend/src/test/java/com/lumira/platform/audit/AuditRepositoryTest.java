package com.lumira.platform.audit;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named="LUMIRA_TEST_DB_URL",matches=".+")
class AuditRepositoryTest {
    JdbcTemplate admin,jdbc; String schema; AuditRepository repository;

    @BeforeEach void setup() throws Exception {
        var url=System.getenv("LUMIRA_TEST_DB_URL");var user=System.getenv("LUMIRA_TEST_DB_USER");var password=System.getenv("LUMIRA_TEST_DB_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));schema="qa_audit_retention_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);jdbc=new JdbcTemplate(ds);repository=new AuditRepository(JdbcClient.create(ds));
        jdbc.execute("CREATE TABLE platform_audit(id UUID PRIMARY KEY,actor VARCHAR(100) NOT NULL,action VARCHAR(100) NOT NULL,target_id UUID NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE INDEX platform_audit_created_idx ON platform_audit(created_at DESC)");
    }

    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}

    @Test void deletesOnlyOneOldBatchAndLeavesNewEvents() {
        var oldId=UUID.randomUUID();var oldId2=UUID.randomUUID();var freshId=UUID.randomUUID();var target=UUID.randomUUID();
        jdbc.update("INSERT INTO platform_audit(id,actor,action,target_id,created_at) VALUES(?,?,?,?,?),(?,?,?,?,?),(?,?,?,?,?)",
            oldId,"old","x",target,OffsetDateTime.parse("2026-01-01T00:00:00Z"),oldId2,"old","x",target,OffsetDateTime.parse("2026-01-02T00:00:00Z"),freshId,"new","x",target,OffsetDateTime.parse("2026-09-01T00:00:00Z"));
        assertEquals(1,repository.purgeBefore(OffsetDateTime.parse("2026-02-01T00:00:00Z"),1));
        assertEquals(List.of(oldId2,freshId),jdbc.queryForList("SELECT id FROM platform_audit ORDER BY created_at,id",UUID.class));
    }
}
