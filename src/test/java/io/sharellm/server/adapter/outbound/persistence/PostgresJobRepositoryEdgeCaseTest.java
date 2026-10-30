package io.sharellm.server.adapter.outbound.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.sharellm.server.application.settings.QueueSettings;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Database races that cannot be produced deterministically against a real PostgreSQL. */
class PostgresJobRepositoryEdgeCaseTest {
    private final JdbcTemplate db = mock(JdbcTemplate.class);
    private final PostgresJobRepository repository =
            new PostgresJobRepository(db, new QueueSettings(35, 45, 3, 300, 15, 350, 24, 0, 1024));

    @Test
    void concurrentPollFencedByTheUniqueLeaseIndexClaimsNothing() {
        when(db.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenThrow(new DuplicateKeyException("llm_jobs_one_lease_per_worker_idx"));
        assertThat(repository.claim("laptop")).isEmpty();
    }

    @Test
    void missingCountIsZero() {
        when(db.queryForObject(anyString(), eq(Long.class))).thenReturn(null);
        assertThat(repository.countPending()).isZero();
    }

}
