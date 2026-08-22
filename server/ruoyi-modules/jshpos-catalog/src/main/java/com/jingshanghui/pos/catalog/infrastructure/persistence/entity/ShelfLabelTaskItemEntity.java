package com.jingshanghui.pos.catalog.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.time.LocalDateTime;

/** ShelfLabel Owner 的不可变商品价格快照及受控换签状态投影。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lbl_label_task_item")
public class ShelfLabelTaskItemEntity extends TenantEntity {

    /** 任务项主键。 */
    @TableId
    private Long itemId;
    /** 所属价签任务。 */
    private Long taskId;
    /** 来源价格簿。 */
    private Long sourcePriceBookId;
    /** 来源价格项。 */
    private Long sourcePriceItemId;
    /** 来源价格业务版本。 */
    private Integer sourcePriceVersion;
    /** 门店价为 2，租户基础价为 1。 */
    private Integer scopePriority;
    /** 目标门店。 */
    private Long storeId;
    /** 门店名称快照。 */
    private String storeName;
    /** 商品 SKU 主键。 */
    private Long skuId;
    /** 商品 SKU 编码快照。 */
    private String skuCode;
    /** 商品名称快照。 */
    private String productName;
    /** 单位主键。 */
    private Long unitId;
    /** 单位名称快照。 */
    private String unitName;
    /** 首选条码字符串，保留前导零。 */
    private String barcode;
    /** 原价，单位为分；首次定价可为空。 */
    private Long oldPriceMinor;
    /** 新价，单位为分；无回退价时可为空并进入异常。 */
    private Long newPriceMinor;
    /** 币种，商业 V1 为 CNY。 */
    private String currency;
    /** 价格生效时间，UTC。 */
    private LocalDateTime effectiveAt;
    /** PENDING/PREVIEW_READY/REPLACED_CONFIRMED/EXCEPTION/SUPERSEDED。 */
    private String state;
    /** 当前异常摘要，不替代只追加异常事实。 */
    private String exceptionReason;
    /** 商品、单位、条码和价格快照 SHA-256。 */
    private String snapshotSha256;
    /** 创建时间，UTC。 */
    private LocalDateTime createdAt;
    /** 最近状态更新时间，UTC。 */
    private LocalDateTime updatedAt;
    /** 乐观锁版本。 */
    @Version
    private Integer version;
}
