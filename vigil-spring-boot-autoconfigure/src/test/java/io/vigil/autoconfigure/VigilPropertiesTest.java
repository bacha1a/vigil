package io.vigil.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VigilPropertiesTest {

    @Test
    void defaultsAreSensible() {
        var p = new VigilProperties();
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.getBackend()).isEqualTo(VigilProperties.Backend.AUTO);
        assertThat(p.getPodId()).isNull();
        assertThat(p.getCheckpointSizeLimitKb()).isEqualTo(10);
        assertThat(p.getOrphanScanIntervalMs()).isEqualTo(30_000L);
        assertThat(p.getLockTtlSeconds()).isEqualTo(300);
        assertThat(p.getRunHistoryRetention()).isEqualTo(100);
        assertThat(p.getRunHistoryCleanupIntervalMs()).isEqualTo(3_600_000L);
    }

    @Test
    void settersUpdateValues() {
        var p = new VigilProperties();
        p.setEnabled(false);
        p.setBackend(VigilProperties.Backend.JDBC);
        p.setPodId("pod-x");
        p.setCheckpointSizeLimitKb(64);
        p.setOrphanScanIntervalMs(45_000L);
        p.setLockTtlSeconds(120);
        p.setRunHistoryRetention(500);
        p.setRunHistoryCleanupIntervalMs(7_200_000L);

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getBackend()).isEqualTo(VigilProperties.Backend.JDBC);
        assertThat(p.getPodId()).isEqualTo("pod-x");
        assertThat(p.getCheckpointSizeLimitKb()).isEqualTo(64);
        assertThat(p.getOrphanScanIntervalMs()).isEqualTo(45_000L);
        assertThat(p.getLockTtlSeconds()).isEqualTo(120);
        assertThat(p.getRunHistoryRetention()).isEqualTo(500);
        assertThat(p.getRunHistoryCleanupIntervalMs()).isEqualTo(7_200_000L);
    }

    @Test
    void backendEnumHasAllValues() {
        assertThat(VigilProperties.Backend.values())
                .containsExactly(
                        VigilProperties.Backend.JDBC,
                        VigilProperties.Backend.REDIS,
                        VigilProperties.Backend.MONGO,
                        VigilProperties.Backend.DYNAMODB,
                        VigilProperties.Backend.AUTO);
    }
}
