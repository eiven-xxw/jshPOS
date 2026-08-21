package com.jingshanghui.pos.foundation.application.port;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 终端凭据校验后的门店只读端口。
 *
 * <p>调用方只能传入已经从服务端终端注册表得到的租户、组织和门店标识，
 * 本端口再次按租户范围核验门店，禁止使用客户端自报的 tenant_id。</p>
 */
public interface TrustedDeviceStoreContextPort {

    TrustedDeviceStoreContext resolve(String tenantId, Long orgUnitId, Long storeId, Instant at);

    /** 终端登录前允许公开的最小门店上下文。 */
    record TrustedDeviceStoreContext(String storeCode, String storeName, String zoneId,
                                     LocalDate businessDate, String status) {
    }
}
