package com.jingshanghui.pos.catalog.application.model;

import java.time.Instant;
import java.util.List;

/**
 * 货架价签应用层命令与只读投影。
 *
 * <p>所有价格金额均为最小货币单位整数；tenant_id 不出现在任何客户端命令中，
 * 只能由可信会话注入。</p>
 */
public final class ShelfLabelModels {

    private ShelfLabelModels() {
    }

    /**
     * 创建价签模板命令。
     *
     * @param templateCode 租户内模板编码
     * @param templateName 模板名称
     * @param versionNo 业务版本号
     * @param scopeType TENANT 或 STORE
     * @param storeId 门店模板的目标门店
     * @param bodyTemplate 仅包含批准占位符的纯文本模板
     */
    public record CreateTemplateCommand(String templateCode, String templateName, int versionNo,
                                        String scopeType, Long storeId, String bodyTemplate,
                                        String idempotencyKey, String correlationId) {
    }

    /**
     * 价签模板视图。
     *
     * @param templateId 模板主键
     * @param templateCode 模板编码
     * @param templateName 模板名称
     * @param versionNo 业务版本号
     * @param scopeType 作用域
     * @param storeId 可选门店
     * @param bodyTemplate 纯文本模板
     * @param state DRAFT/PUBLISHED/RETIRED
     * @param contentSha256 发布内容摘要
     * @param publishedAt 发布时间
     * @param version 乐观锁版本
     */
    public record TemplateView(Long templateId, String templateCode, String templateName, Integer versionNo,
                               String scopeType, Long storeId, String bodyTemplate, String state,
                               String contentSha256, Instant publishedAt, Integer version) {
    }

    /**
     * 价格发布或停用后交给 ShelfLabel Owner 的不可变通知。
     *
     * @param eventType PRICE_BOOK_PUBLISHED 或 PRICE_BOOK_RETIRED
     * @param priceBookId 价格簿主键
     * @param priceVersion 价格业务版本
     * @param scopeType TENANT_BASE 或 STORE
     * @param storeId 门店价作用域门店
     * @param contentSha256 已发布价格内容摘要
     * @param occurredAt 权威事件时间
     */
    public record PriceBookEvent(String eventType, Long priceBookId, Integer priceVersion,
                                 String scopeType, Long storeId, String contentSha256, Instant occurredAt) {
    }

    /**
     * 价签任务摘要。
     *
     * @param taskId 任务主键
     * @param sourceEventKey 稳定来源事件键
     * @param sourceEventType 来源事件类型
     * @param sourcePriceBookId 来源价格簿
     * @param sourcePriceVersion 来源价格版本
     * @param storeId 目标门店
     * @param storeName 冻结门店名称
     * @param effectiveAt 价格生效时间
     * @param state 软件任务状态，不表达打印成功
     * @param itemCount 任务项总数
     * @param pendingCount 待处理数
     * @param exceptionCount 异常数
     * @param createdAt 创建时间
     * @param version 乐观锁版本
     */
    public record TaskView(Long taskId, String sourceEventKey, String sourceEventType,
                           Long sourcePriceBookId, Integer sourcePriceVersion, Long storeId,
                           String storeName, Instant effectiveAt, String state, Integer itemCount,
                           Integer pendingCount, Integer exceptionCount, Instant createdAt, Integer version) {
    }

    /**
     * 价签任务项冻结快照。
     *
     * @param itemId 任务项主键
     * @param taskId 所属任务
     * @param storeId 目标门店
     * @param storeName 冻结门店名称
     * @param skuId 商品 SKU 主键
     * @param skuCode 商品 SKU 编码
     * @param productName 商品名称快照
     * @param unitId 单位主键
     * @param unitName 单位名称快照
     * @param barcode 条码字符串，保留前导零
     * @param oldPriceMinor 原价，首次定价时为空
     * @param newPriceMinor 新价，价格停用且无回退价时为空
     * @param currency 币种
     * @param sourcePriceVersion 来源价格版本
     * @param effectiveAt 生效时间
     * @param state 换签状态
     * @param exceptionReason 当前异常摘要
     * @param version 乐观锁版本
     */
    public record TaskItemView(Long itemId, Long taskId, Long storeId, String storeName, Long skuId,
                               String skuCode, String productName, Long unitId, String unitName,
                               String barcode, Long oldPriceMinor, Long newPriceMinor, String currency,
                               Integer sourcePriceVersion, Instant effectiveAt, String state,
                               String exceptionReason, Integer version) {
    }

    /**
     * 结构化价签预览；renderedText 只能作为纯文本展示。
     *
     * @param templateId 命中的已发布模板
     * @param templateVersion 模板版本
     * @param templateSha256 模板摘要
     * @param item 任务项冻结快照
     * @param renderedText 已完成安全替换的纯文本
     * @param previewSha256 预览摘要
     */
    public record PreviewView(Long templateId, Integer templateVersion, String templateSha256,
                              TaskItemView item, String renderedText, String previewSha256) {
    }

    /**
     * 任务详情。
     *
     * @param task 任务摘要
     * @param items 冻结任务项
     */
    public record TaskDetailView(TaskView task, List<TaskItemView> items) {
        public TaskDetailView {
            items = List.copyOf(items);
        }
    }
}
