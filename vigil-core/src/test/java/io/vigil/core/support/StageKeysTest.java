package io.vigil.core.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StageKeysTest {

    enum Stage      { ARCHIVE, CHARGE }
    enum OtherStage { ARCHIVE }
    enum MultiWord  { SEND_EMAIL, FETCH_PAGE }

    @Test
    @DisplayName("toDbKey - produces '{SimpleClassName}.{CONSTANT}' format")
    void toDbKeyFormat() {
        assertThat(StageKeys.toDbKey(Stage.ARCHIVE)).isEqualTo("Stage.ARCHIVE");
        assertThat(StageKeys.toDbKey(Stage.CHARGE)).isEqualTo("Stage.CHARGE");
    }

    @Test
    @DisplayName("toDbKey - different enum types with the same constant name produce distinct keys")
    void differentEnumsWithSameConstantProduceDifferentKeys() {
        assertThat(StageKeys.toDbKey(Stage.ARCHIVE))
                .isNotEqualTo(StageKeys.toDbKey(OtherStage.ARCHIVE));
    }

    @Test
    @DisplayName("toDbKey - enum constants with underscores are preserved exactly")
    void enumConstantWithUnderscorePreservesUnderscore() {
        assertThat(StageKeys.toDbKey(MultiWord.SEND_EMAIL)).isEqualTo("MultiWord.SEND_EMAIL");
        assertThat(StageKeys.toDbKey(MultiWord.FETCH_PAGE)).isEqualTo("MultiWord.FETCH_PAGE");
    }
}
