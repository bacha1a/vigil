package io.vigil.autoconfigure;

import io.vigil.autoconfigure.schema.VigilSchemaInitializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class VigilSchemaInitializerTest {

    @Nested
    @DisplayName("afterPropertiesSet")
    class AfterPropertiesSet {

        @Test
        @DisplayName("executes CREATE TABLE for vigil_job_locks plus its expiry index")
        void createsLocksTable() throws Exception {
            var jdbc = mock(JdbcTemplate.class);
            var initializer = new VigilSchemaInitializer(jdbc);

            initializer.afterPropertiesSet();

            verify(jdbc, times(2)).execute(contains("vigil_job_locks"));
            verify(jdbc).execute(contains("idx_vigil_locks_expiry"));
            verify(jdbc).execute(contains("status IN ('FREE','HELD','ORPHANED','PAUSED')"));
        }

        @Test
        @DisplayName("executes CREATE TABLE for vigil_job_checkpoints plus its (job_name, run_id) index")
        void createsCheckpointsTable() throws Exception {
            var jdbc = mock(JdbcTemplate.class);
            var initializer = new VigilSchemaInitializer(jdbc);

            initializer.afterPropertiesSet();

            verify(jdbc, times(2)).execute(contains("vigil_job_checkpoints"));
            verify(jdbc).execute(contains("idx_vigil_checkpoints_job"));
        }

        @Test
        @DisplayName("executes CREATE TABLE IF NOT EXISTS for vigil_job_runs")
        void createsJobRunsTable() throws Exception {
            var jdbc = mock(JdbcTemplate.class);
            var initializer = new VigilSchemaInitializer(jdbc);

            initializer.afterPropertiesSet();

            verify(jdbc).execute(contains("vigil_job_runs"));
        }

        @Test
        @DisplayName("executes exactly five DDL statements - three tables plus two indexes")
        void executesFiveDdlStatements() throws Exception {
            var jdbc = mock(JdbcTemplate.class);
            var initializer = new VigilSchemaInitializer(jdbc);

            initializer.afterPropertiesSet();

            verify(jdbc, times(5)).execute(org.mockito.ArgumentMatchers.anyString());
        }
    }
}
