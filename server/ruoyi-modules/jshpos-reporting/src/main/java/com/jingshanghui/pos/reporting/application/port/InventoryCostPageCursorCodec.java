package com.jingshanghui.pos.reporting.application.port;

import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort.InventoryCostKey;

/** 库存成本报表游标签名端口；游标必须绑定可信租户、冻结筛选摘要和投影版本。 */
public interface InventoryCostPageCursorCodec {
    String encode(CursorEnvelope envelope);

    CursorEnvelope decodeAndVerify(String token, String expectedTenantId, String expectedFilterSha256,
                                   String expectedProjectionVersion);

    /**
     * 游标安全信封。
     * @param tenantId 可信租户
     * @param filterSha256 冻结筛选摘要
     * @param projectionVersion 冻结投影版本
     * @param after 上一批最后一行；仅导出初始检查点允许 null
     */
    record CursorEnvelope(String tenantId, String filterSha256, String projectionVersion,
                          InventoryCostKey after) {
    }
}
