package com.jingshanghui.pos.inventory.application.port;

import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReturnCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.SaleCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.TransferReceiveCommand;

import java.time.LocalDate;

/**
 * 采购、订单、退货、盘点和调拨 Owner 提交批次拆分的进程内受控端口。
 *
 * <p>端口不接受 tenant_id；批次数量必须与已落库的仓库总账来源事件逐行守恒。</p>
 */
public interface AuthoritativeLotMovementPort {
    /** 从可信门店行业绑定和 Catalog 策略判定是否需要批次路径。 */
    boolean requiresLotTracking(Long storeId, Long skuId, LocalDate businessDate);
    ApplyResult receive(ReceiveCommand command);
    ApplyResult allocateSale(SaleCommand command);
    ApplyResult returnOriginal(ReturnCommand command);
    ApplyResult applyExplicit(ExplicitCommand command);

    /** 调拨目的仓按原发出分配继承批次身份，禁止客户端自报效期。 */
    ApplyResult receiveTransfer(TransferReceiveCommand command);
}
