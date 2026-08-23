package com.jingshanghui.pos.foundation.application.port;

/**
 * Foundation 对技术租户记录的唯一写入端口。
 *
 * <p>SaaS Owner 只能编排商业开户，禁止直接依赖 RuoYi System 或写入其私有表。</p>
 */
public interface TenantProvisioningPort {

    ProvisionedTenant provision(ProvisionTenant command);

    void changeStatus(String tenantId, TechnicalTenantStatus status);

    enum TechnicalTenantStatus { ACTIVE, DISABLED }

    /** 一次性开户输入；密码只能交给技术租户 Owner，不得写入 SaaS 表、日志或制品。 */
    record ProvisionTenant(String companyName, String contactName, String contactPhone,
                           String bootstrapUsername, char[] bootstrapPassword,
                           Long platformPackageId, long accountLimit) { }

    /** Foundation 分配的技术租户引用。 */
    record ProvisionedTenant(Long technicalRecordId, String tenantId) { }
}
