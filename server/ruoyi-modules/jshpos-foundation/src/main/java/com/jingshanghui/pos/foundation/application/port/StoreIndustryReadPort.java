package com.jingshanghui.pos.foundation.application.port;

import java.time.LocalTime;

/**
 * 向领域模块提供门店当前已发布行业模板身份的可信只读端口。
 *
 * <p>调用方不得接受客户端 industry 代替本端口结果。</p>
 */
public interface StoreIndustryReadPort {

    IndustryBinding requireCurrentIndustry(Long storeId);

    /**
     * 当前门店行业模板的不可变身份。
     *
     * @param storeId 门店平台主键
     * @param templateId 模板平台主键
     * @param templateVersionId 已发布模板版本主键
     * @param versionNo 业务版本号
     * @param industry 行业代码
     * @param contentSha256 模板内容摘要
     */
    record IndustryBinding(Long storeId, Long templateId, Long templateVersionId,
                           Integer versionNo, String industry, String contentSha256,
                           String zoneId, LocalTime businessDayStart) {
    }
}
