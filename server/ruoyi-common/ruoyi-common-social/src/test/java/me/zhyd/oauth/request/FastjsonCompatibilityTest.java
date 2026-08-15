package me.zhyd.oauth.request;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the fastjson 1.x API surface used by the copied JustAuth adapters.
 * The implementation is the fastjson2 compatibility artifact, not fastjson 1.x.
 */
@Tag("local")
@Tag("dev")
@Tag("prod")
class FastjsonCompatibilityTest {

    @Test
    void shouldPreserveAdapterJsonOperationsAfterCompatibilityUpgrade() {
        Map<String, Object> token = new LinkedHashMap<>();
        token.put("accessToken", "token-value");
        token.put("expireIn", 7200);
        token.put("visitor", false);

        String json = JSONObject.toJSONString(token);
        JSONObject parsed = JSONObject.parseObject(json);

        assertThat(parsed.containsKey("accessToken")).isTrue();
        assertThat(parsed.getString("accessToken")).isEqualTo("token-value");
        assertThat(parsed.getIntValue("expireIn")).isEqualTo(7200);
        assertThat(parsed.getBooleanValue("visitor")).isFalse();

        JSONObject detail = new JSONObject();
        detail.put("user_ticket", "ticket-value");
        parsed.putAll(detail);

        assertThat(JSONObject.parseObject(parsed.toJSONString()).getString("user_ticket"))
            .isEqualTo("ticket-value");
    }
}
