package com.jingshanghui.pos.reporting.infrastructure.security;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class HmacReportDownloadTokenProtectorTest {
    @Test void issuesRandomTokensAndStableNonPlaintextHashes() {
        var protector=new HmacReportDownloadTokenProtector(new byte[32]);
        var first=protector.issue(); var second=protector.issue();
        assertThat(first.plaintext()).isNotEqualTo(second.plaintext()).hasSizeGreaterThanOrEqualTo(32);
        assertThat(first.sha256()).hasSize(64).isEqualTo(protector.hash(first.plaintext()))
            .doesNotContain(first.plaintext());
    }
    @Test void rejectsWeakKeysAndBadTokensAndFailsClosedWithoutConfiguration() {
        assertThatThrownBy(() -> new HmacReportDownloadTokenProtector(new byte[16]))
            .isInstanceOf(IllegalArgumentException.class);
        var protector=new HmacReportDownloadTokenProtector(new byte[32]);
        assertThatThrownBy(() -> protector.hash("short")).isInstanceOf(IllegalArgumentException.class);
        var rejecting=new RejectingReportDownloadTokenProtector();
        assertThatThrownBy(rejecting::issue).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> rejecting.hash("x".repeat(32))).isInstanceOf(ServiceException.class);
    }
}
