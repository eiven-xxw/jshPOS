package com.jingshanghui.pos.service.application.port;

/**
 * Service Owner 读取 SaaS 套餐权益的最小只读端口。
 *
 * <p>端口只暴露服务运营所需的允许/拒绝结论，禁止把 SaaS 应用服务、
 * 持久化模型或配额写能力泄漏到 Service 应用层。</p>
 */
public interface ServiceEntitlementReadPort {

    /** 按功能编码读取当前可信租户的服务端授权结论。 */
    AccessDecision decide(String featureCode);

    /**
     * 权益只读结论。
     *
     * @param allowed 是否允许访问
     * @param reason  SaaS 边界返回的可审计原因码
     */
    record AccessDecision(boolean allowed, String reason) {
    }
}
