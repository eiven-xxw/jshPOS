package com.jingshanghui.pos.member.infrastructure.crypto;

import org.junit.jupiter.api.Test;
import java.security.SecureRandom;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

/** 验证身份 HMAC 检索、随机 IV、AEAD 密文和无密钥失败关闭。 */
class AesGcmMemberIdentityProtectorTest {
    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length]; for (int i=0;i<length;i++) value[i]=(byte)(seed+i); return value;
    }

    @Test void protectsSyntheticIdentityWithoutPersistingCleartext() {
        var protector = new AesGcmMemberIdentityProtector(bytes(32,1), bytes(32,41), 3,
            new FixedRandom((byte)7));
        var protectedValue = protector.protect("MOBILE", "+8613800000000");
        assertThat(protectedValue.lookupHmac()).hasSize(64).isEqualTo(
            protector.lookupHmac("MOBILE", "+8613800000000"));
        assertThat(protectedValue.maskedValue()).isEqualTo("+86****0000");
        assertThat(protectedValue.keyVersion()).isEqualTo(3);
        assertThat(new String(Base64.getDecoder().decode(protectedValue.cipherText())))
            .doesNotContain("13800000000");
        assertThat(protector.lookupHmac("CARD", "+8613800000000"))
            .isNotEqualTo(protectedValue.lookupHmac());
    }

    @Test void validatesEveryKeyBoundary() {
        assertThatThrownBy(() -> new AesGcmMemberIdentityProtector(null,bytes(32,1),1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmMemberIdentityProtector(bytes(31,1),bytes(32,1),1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmMemberIdentityProtector(bytes(32,1),null,1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmMemberIdentityProtector(bytes(32,1),bytes(15,1),1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmMemberIdentityProtector(bytes(32,1),bytes(16,1),0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AesGcmMemberIdentityProtector(bytes(32,1),bytes(16,1),1)).doesNotThrowAnyException();
        assertThatCode(() -> new AesGcmMemberIdentityProtector(bytes(32,1),bytes(24,1),1)).doesNotThrowAnyException();
    }

    @Test void rejectsEveryIdentityOperationWhenExternalKeyIsMissing() {
        var rejecting = new RejectingMemberIdentityProtector();
        assertThatThrownBy(() -> rejecting.protect("CARD","SYNTHETIC-1"))
            .hasMessageContaining("MEM-CRYPTO-004");
        assertThatThrownBy(() -> rejecting.lookupHmac("CARD","SYNTHETIC-1"))
            .hasMessageContaining("MEM-CRYPTO-004");
    }

    private static final class FixedRandom extends SecureRandom {
        private final byte value; private FixedRandom(byte value) { this.value=value; }
        @Override public void nextBytes(byte[] bytes) { java.util.Arrays.fill(bytes,value); }
    }
}
