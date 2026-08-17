package com.jingshanghui.pos.transfer.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransferHashTest {
    @Test
    void canonicalEncodingIsLengthDelimitedAndDeterministic() {
        String first = TransferHash.canonical(List.of("ab", "c"));
        String second = TransferHash.canonical(List.of("a", "bc"));
        assertThat(first).isNotEqualTo(second);
        assertThat(TransferHash.sha256(first)).hasSize(64).isEqualTo(TransferHash.sha256(first));
        assertThat(TransferHash.canonical(List.of())).isEmpty();
    }
}
