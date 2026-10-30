package io.sharellm.server;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for tests against a real PostgreSQL. All subclasses share one container and one Spring context.
 * Housekeeping intervals are an hour, so scheduled runs never race the tests.
 */
@SpringBootTest(properties = {
    "sharellm.public-keys=owner-a, owner-b",
    "sharellm.worker-key=worker-key",
    "sharellm.wait-seconds=10",
    "sharellm.poll-seconds=2",
    "sharellm.result-poll-millis=50",
    "sharellm.reclaim-interval-ms=3600000",
    "sharellm.cleanup-interval-ms=3600000"})
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired protected JdbcTemplate db;

    @BeforeEach
    void emptyTables() { db.update("TRUNCATE llm_results, llm_jobs, llm_workers"); }
}
