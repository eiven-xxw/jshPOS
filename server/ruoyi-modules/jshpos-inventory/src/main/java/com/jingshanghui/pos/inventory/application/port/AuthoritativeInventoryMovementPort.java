package com.jingshanghui.pos.inventory.application.port;

import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 盘点、采购、调拨与退货退款 Owner 提交库存数量事实的进程内受控端口。
 *
 * <p>端口不接受 tenant_id；调用者必须先持久化并验证自己的权威单据，外部 Controller 不得直接暴露本端口。</p>
 */
public interface AuthoritativeInventoryMovementPort {

    ApplyResult applyOwnedMovement(OwnedMovement command);

    /** 一次来源聚合的原子库存命令。 */
    record OwnedMovement(String eventId, String sourceType, String sourceId, String warehouseId,
                         Long storeId, LocalDate businessDate, String correlationId,
                         List<OwnedMovementLine> lines, String originalSourceId) {
        public OwnedMovement {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        public OwnedMovement(String eventId, String sourceType, String sourceId, String warehouseId,
                             Long storeId, LocalDate businessDate, String correlationId,
                             List<OwnedMovementLine> lines) {
            this(eventId, sourceType, sourceId, warehouseId, storeId, businessDate, correlationId, lines, null);
        }
    }

    /** 来源行的基础单位精确数量；数量为正，方向由 movementType 决定。 */
    record OwnedMovementLine(String sourceLineId, Long skuId, Long baseUnitId,
                             BigDecimal quantity, MovementType movementType) {
    }
}
