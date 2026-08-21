package com.jingshanghui.pos.foundation.application.port;

import java.util.Optional;

/**
 * 已发布配置的跨 Owner 只读端口。
 *
 * <p>调用方只提交业务配置代码与已授权门店，不提交 tenant_id；实现必须从可信上下文取租户，
 * 并按“门店覆盖租户”的固定优先级返回不可变版本。</p>
 */
public interface PublishedConfigReadPort {

    Optional<PublishedConfig> find(String templateCode, Long storeId);

    /** 已发布配置快照；内容摘要用于调用方再次校验和冻结。 */
    record PublishedConfig(String tenantId, Long templateId, Long configVersionId,
                           Integer versionNo, String contentJson, String contentSha256) {
    }
}
