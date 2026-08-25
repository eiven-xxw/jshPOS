package com.jingshanghui.pos.order.application.port;

import com.jingshanghui.pos.order.application.model.OrderCommands.CloseSyncedShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.OpenSyncedShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.RecordCashMovement;
import com.jingshanghui.pos.order.application.model.OrderCommands.RequestNoSaleDrawer;
import com.jingshanghui.pos.order.application.model.OrderViews.CashMovementView;
import com.jingshanghui.pos.order.application.model.OrderViews.DrawerEventView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;

/**
 * 订单 Owner 对同步模块发布的班次事实提交端口。
 *
 * <p>调用方只能提交已经过可信设备上下文校验的版本化命令；班次状态机、
 * 幂等、权限、审计和事务边界仍由订单 Owner 独占。</p>
 */
public interface ShiftSubmissionPort {

    /** 接收 POS 已冻结的开班事实，并保留原班次标识。 */
    ShiftView openSynced(OpenSyncedShift command);

    /**
     * 接收 POS 已冻结的关班事实；本地前序版本仅用于收敛校验，关闭仍以锁定后的
     * 服务端权威版本和现金账本为准。
     */
    ShiftView closeSynced(CloseSyncedShift command);

    /** 接收 POS 已冻结的非销售现金事实。 */
    CashMovementView recordCashMovement(RecordCashMovement command);

    /** 接收 POS 已冻结的钱箱请求事实；不得触发设备。 */
    DrawerEventView requestNoSaleDrawer(RequestNoSaleDrawer command);
}
