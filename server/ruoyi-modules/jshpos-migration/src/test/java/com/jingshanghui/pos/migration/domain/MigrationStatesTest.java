package com.jingshanghui.pos.migration.domain;

import org.junit.jupiter.api.Test;

import static com.jingshanghui.pos.migration.domain.MigrationStates.BatchState.*;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationStatesTest {
    @Test
    void allowsOnlyNamedForwardAndRecoveryTransitions() {
        assertThat(MigrationStates.canTransition(UPLOADED, PREFLIGHTING)).isTrue();
        assertThat(MigrationStates.canTransition(PREFLIGHTING, READY)).isTrue();
        assertThat(MigrationStates.canTransition(PREFLIGHT_FAILED, PREFLIGHTING)).isFalse();
        assertThat(MigrationStates.canTransition(PREFLIGHT_FAILED, READY)).isFalse();
        assertThat(MigrationStates.canTransition(READY, APPROVED)).isTrue();
        assertThat(MigrationStates.canTransition(IMPORTING, COMPENSATION_REQUIRED)).isTrue();
        assertThat(MigrationStates.canTransition(FAILED, IMPORTING)).isTrue();
        assertThat(MigrationStates.canTransition(RECONCILED, ACTIVATION_PENDING)).isTrue();
        assertThat(MigrationStates.canTransition(ACTIVATED, CLEANED)).isTrue();
        assertThat(MigrationStates.canTransition(ACTIVATED, ACTIVATED)).isTrue();
        assertThat(MigrationStates.canTransition(CLEANED, ACTIVATED)).isFalse();
        assertThat(MigrationStates.canTransition(UPLOADED, ACTIVATED)).isFalse();
        assertThat(MigrationStates.canTransition(READY, IMPORTING)).isFalse();
        assertThat(MigrationStates.CheckpointState.values()).containsExactly(
            MigrationStates.CheckpointState.PENDING, MigrationStates.CheckpointState.APPLIED,
            MigrationStates.CheckpointState.FAILED);
    }
}
