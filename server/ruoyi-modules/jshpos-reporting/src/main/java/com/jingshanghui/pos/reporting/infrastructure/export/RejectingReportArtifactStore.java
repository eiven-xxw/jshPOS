package com.jingshanghui.pos.reporting.infrastructure.export;

import com.jingshanghui.pos.reporting.application.port.ReportArtifactStore;
import org.dromara.common.core.exception.ServiceException;

/** 制品根目录缺失时失败关闭导出生成与下载。 */
public final class RejectingReportArtifactStore implements ReportArtifactStore {
    @Override public void put(String objectKey, byte[] content) { throw unavailable(); }
    @Override public byte[] get(String objectKey) { throw unavailable(); }
    @Override public void delete(String objectKey) { throw unavailable(); }
    private ServiceException unavailable() { return new ServiceException("RPT-EXP-006: 导出制品存储未配置", 503); }
}
