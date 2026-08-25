package com.jingshanghui.pos.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/** ShelfLabel Owner 的按门店换签任务当前投影实体。 */
@Data
@TableName("lbl_label_task")
public class ShelfLabelTaskEntity {

    /** 由可信上下文注入的租户标识。 */
    private String tenantId;

    /** 任务主键。 */
    @TableId
    private Long taskId;
    /** 价格事件的稳定来源键。 */
    private String sourceEventKey;
    /** PRICE_BOOK_PUBLISHED 或 PRICE_BOOK_RETIRED。 */
    private String sourceEventType;
    /** 来源事件内容 SHA-256。 */
    private String sourceEventSha256;
    /** 来源价格簿主键。 */
    private Long sourcePriceBookId;
    /** 来源价格业务版本。 */
    private Integer sourcePriceVersion;
    /** 目标门店主键。 */
    private Long storeId;
    /** 生成任务时的门店名称快照。 */
    private String storeName;
    /** 此任务最早价格生效时间，UTC。 */
    private LocalDateTime effectiveAt;
    /** 软件任务状态，不代表真实打印状态。 */
    private String state;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 最近投影更新时间，UTC。 */
    private LocalDateTime updatedAt;
    /** 乐观锁版本。 */
    @Version
    private Integer version;
}
