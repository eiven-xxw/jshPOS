package com.jingshanghui.pos.reporting.application.port;

/** 安全导出制品存储端口；对象键只能由 Reporting 应用服务生成。 */
public interface ReportArtifactStore {
    void put(String objectKey, byte[] content);
    byte[] get(String objectKey);
    void delete(String objectKey);
}
