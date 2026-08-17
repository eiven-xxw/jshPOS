package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class CanonicalReportHashTest {
    @Test void isDeterministicAndRejectsNull() {
        assertThat(CanonicalReportHash.sha256("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(CanonicalReportHash.sha256(new byte[]{97,98,99}))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(CanonicalReportHash.sha256("abc")).isEqualTo(CanonicalReportHash.sha256("abc"));
        assertThatThrownBy(() -> CanonicalReportHash.sha256((String)null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> CanonicalReportHash.sha256((byte[])null)).isInstanceOf(ServiceException.class);
    }
}
