<template>
  <div class="operation-panel">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="采购与调拨不直接修改余额"
      description="采购确认、采购退货、调拨发出与收货只向各 Owner 提交命令；数量和成本效果由 Inventory/Costing 追加正式流水。"
    />

    <el-tabs class="mt-3" type="border-card">
      <el-tab-pane label="供应商与采购">
        <el-card shadow="never">
          <template #header><span>供应商</span></template>
          <el-form :inline="true">
            <el-form-item label="供应商 ULID"><el-input v-model="supplier.supplierId" class="id-input" /></el-form-item>
            <el-form-item label="编码"><el-input v-model="supplier.code" class="small-input" /></el-form-item>
            <el-form-item label="名称"><el-input v-model="supplier.name" class="medium-input" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['procurement:supplier:create']" type="primary" @click="submitSupplier">创建</el-button>
              <el-button v-hasPermi="['procurement:supplier:state']" type="warning" @click="supplierState('SUSPENDED')">暂停</el-button>
              <el-button v-hasPermi="['procurement:supplier:state']" type="success" @click="supplierState('ACTIVE')">启用</el-button>
              <el-button v-hasPermi="['procurement:supplier:state']" type="danger" @click="supplierState('BLOCKED')">阻断</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="supplierResult" :column="4" border>
            <el-descriptions-item label="状态">{{ supplierResult.status || supplierResult.state }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ supplierResult.version }}</el-descriptions-item>
            <el-descriptions-item label="编码">{{ supplierResult.code }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{ supplierResult.name }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card class="mt-3" shadow="never">
          <template #header><span>采购单与收退货</span></template>
          <el-form :inline="true">
            <el-form-item label="采购单 ULID"><el-input v-model="order.orderId" class="id-input" /></el-form-item>
            <el-form-item label="门店 ID"><el-input v-model="order.storeId" class="small-input" /></el-form-item>
            <el-form-item label="仓库 ULID"><el-input v-model="order.warehouseId" class="id-input" /></el-form-item>
            <el-form-item label="SKU/单位"
              ><el-input v-model="order.skuId" class="small-input" /> / <el-input v-model="order.unitId" class="small-input"
            /></el-form-item>
            <el-form-item label="数量"><el-input v-model="order.quantity" class="small-input" /></el-form-item>
            <el-form-item label="单价（分）"><el-input v-model="order.unitPriceMinor" class="small-input" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['procurement:order:read']" @click="loadOrder">查询</el-button>
              <el-button v-hasPermi="['procurement:order:create']" type="primary" @click="createOrder">创建</el-button>
              <el-button v-hasPermi="['procurement:order:submit']" @click="orderAction('submit')">提交</el-button>
              <el-button v-hasPermi="['procurement:order:approve']" type="warning" @click="orderAction('approve')">审批</el-button>
              <el-button v-hasPermi="['procurement:order:close']" type="danger" @click="orderAction('close')">关闭</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="orderDetail" :column="4" border>
            <el-descriptions-item label="状态">{{ orderDetail.head.status }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ orderDetail.head.version }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ orderDetail.head.supplierId }}</el-descriptions-item>
            <el-descriptions-item label="预计日期">{{ orderDetail.head.expectedDate }}</el-descriptions-item>
          </el-descriptions>
          <el-table v-if="orderDetail" :data="orderDetail.lines" border class="mt-3" max-height="220">
            <el-table-column prop="orderLineId" label="采购行" min-width="220" />
            <el-table-column prop="skuId" label="SKU" />
            <el-table-column prop="orderedQuantity" label="订购数量" />
            <el-table-column prop="receivedQuantity" label="已收数量" />
            <el-table-column prop="unitPriceMinor" label="单价（分）" />
          </el-table>
          <el-form v-if="orderDetail" :inline="true" class="mt-3">
            <el-form-item label="收货 ULID"><el-input v-model="receipt.receiptId" class="id-input" /></el-form-item>
            <el-form-item label="收货数量"><el-input v-model="receipt.quantity" class="small-input" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['procurement:receipt:read']" @click="loadReceipt">查询收货</el-button>
              <el-button v-hasPermi="['procurement:receipt:create']" type="primary" @click="createReceipt">创建收货</el-button>
              <el-button v-hasPermi="['procurement:receipt:confirm']" type="warning" @click="confirmReceipt">确认收货入账</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="receiptDetail" :column="3" border class="mt-3">
            <el-descriptions-item label="状态">{{ receiptDetail.head.status }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ receiptDetail.head.version }}</el-descriptions-item>
            <el-descriptions-item label="关联标识">{{ receiptDetail.head.correlationId }}</el-descriptions-item>
          </el-descriptions>
          <el-form v-if="receiptDetail" :inline="true" class="mt-3">
            <el-form-item label="退货 ULID"><el-input v-model="purchaseReturn.purchaseReturnId" class="id-input" /></el-form-item>
            <el-form-item label="退货数量"><el-input v-model="purchaseReturn.quantity" class="small-input" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['procurement:return:create']" type="primary" @click="createReturn">创建退货</el-button>
              <el-button v-hasPermi="['procurement:return:submit']" @click="returnAction('submit')">提交退货</el-button>
              <el-button v-hasPermi="['procurement:return:approve']" type="danger" @click="returnAction('approve')">审批退货入账</el-button>
            </el-form-item>
          </el-form>
          <el-descriptions v-if="purchaseReturn.result" :column="3" border>
            <el-descriptions-item label="状态">{{ purchaseReturn.result.status || purchaseReturn.result.state }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ purchaseReturn.result.version }}</el-descriptions-item>
            <el-descriptions-item label="退货 ID">{{ purchaseReturn.result.purchaseReturnId }}</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="基础调拨">
        <el-form :inline="true">
          <el-form-item label="调拨 ULID"><el-input v-model="transfer.transferId" class="id-input" /></el-form-item>
          <el-form-item label="来源门店/仓"
            ><el-input v-model="transfer.sourceStoreId" class="small-input" /> / <el-input v-model="transfer.sourceWarehouseId" class="id-input"
          /></el-form-item>
          <el-form-item label="目的门店/仓"
            ><el-input v-model="transfer.destinationStoreId" class="small-input" /> /
            <el-input v-model="transfer.destinationWarehouseId" class="id-input"
          /></el-form-item>
          <el-form-item label="SKU/单位/数量">
            <el-input v-model="transfer.skuId" class="small-input" /> / <el-input v-model="transfer.unitId" class="small-input" /> /
            <el-input v-model="transfer.quantity" class="small-input" />
          </el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['transfer:order:read']" @click="loadTransfer">查询</el-button>
            <el-button v-hasPermi="['transfer:order:create']" type="primary" @click="createNewTransfer">创建</el-button>
            <el-button v-hasPermi="['transfer:order:submit']" @click="transferAction('submit')">提交</el-button>
            <el-button v-hasPermi="['transfer:order:approve']" type="warning" @click="transferAction('approve')">审批</el-button>
            <el-button v-hasPermi="['transfer:order:cancel']" type="danger" @click="transferAction('cancel')">取消</el-button>
          </el-form-item>
        </el-form>
        <el-descriptions v-if="transferDetail" :column="4" border>
          <el-descriptions-item label="状态">{{ transferDetail.head.status }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ transferDetail.head.version }}</el-descriptions-item>
          <el-descriptions-item label="来源仓">{{ transferDetail.head.sourceWarehouseId }}</el-descriptions-item>
          <el-descriptions-item label="目的仓">{{ transferDetail.head.destinationWarehouseId }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="transferDetail" :data="transferDetail.lines" border class="mt-3" max-height="250">
          <el-table-column prop="transferLineId" label="调拨行" min-width="220" />
          <el-table-column prop="skuId" label="SKU" />
          <el-table-column prop="requestedQuantity" label="申请" />
          <el-table-column prop="dispatchedQuantity" label="发出" />
          <el-table-column prop="receivedQuantity" label="收货" />
          <el-table-column prop="differenceQuantity" label="差异" />
        </el-table>
        <el-form v-if="transferDetail" :inline="true" class="mt-3">
          <el-form-item label="本次数量"><el-input v-model="transfer.operationQuantity" class="small-input" /></el-form-item>
          <el-form-item label="最终收货"><el-switch v-model="transfer.finalReceipt" /></el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['transfer:dispatch:post']" type="warning" @click="dispatch">确认发出</el-button>
            <el-button v-hasPermi="['transfer:receipt:post']" type="danger" @click="receive">确认收货</el-button>
          </el-form-item>
        </el-form>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import {
  changeSupplierState,
  confirmProcurementReceipt,
  createProcurementOrder,
  createProcurementReceipt,
  createProcurementReturn,
  createSupplier,
  createTransfer,
  dispatchTransfer,
  getProcurementOrder,
  getProcurementReceipt,
  getTransfer,
  newOperationCommandId,
  receiveTransfer,
  transitionProcurementOrder,
  transitionProcurementReturn,
  transitionTransfer
} from '@/api/operations';
import type { OwnerOperationView, ProcurementOrderDetail, ProcurementReceiptDetail, TransferDetail } from '@/api/operations/types';
import { exactDecimal } from '../model';
import { useControlledOperation } from '../useControlledOperation';

const { runRead, runControlled } = useControlledOperation();
const supplier = reactive({ supplierId: newOperationCommandId(), code: 'SYN-SUP-001', name: '虚构供应商一号' });
const supplierResult = ref<OwnerOperationView>();
const order = reactive({
  orderId: newOperationCommandId(),
  storeId: '1101',
  warehouseId: '01J00000000000000000000011',
  skuId: '101',
  unitId: '1',
  quantity: '1.000000',
  unitPriceMinor: '100',
  expectedDate: new Date().toISOString().slice(0, 10)
});
const orderDetail = ref<ProcurementOrderDetail>();
const receipt = reactive({ receiptId: newOperationCommandId(), quantity: '1.000000' });
const receiptDetail = ref<ProcurementReceiptDetail>();
const purchaseReturn = reactive<{ purchaseReturnId: string; quantity: string; result?: OwnerOperationView }>({
  purchaseReturnId: newOperationCommandId(),
  quantity: '1.000000'
});
const transfer = reactive({
  transferId: newOperationCommandId(),
  sourceStoreId: '1101',
  sourceWarehouseId: '01J00000000000000000000011',
  destinationStoreId: '1102',
  destinationWarehouseId: '01J00000000000000000000012',
  skuId: '101',
  unitId: '1',
  quantity: '1.000000',
  operationQuantity: '1.000000',
  finalReceipt: true
});
const transferDetail = ref<TransferDetail>();

const submitSupplier = async () => {
  const changed = await runControlled({
    owner: 'Procurement.Supplier',
    objectId: supplier.supplierId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '创建租户内供应商主数据，不产生库存或成本效果',
    reason: '新增经审核的虚构供应商',
    execute: (key) => createSupplier({ ...supplier, correlationId: key })
  });
  if (changed) supplierResult.value = changed;
};

const supplierState = async (state: 'ACTIVE' | 'SUSPENDED' | 'BLOCKED') => {
  if (!supplierResult.value) return ElMessage.warning('请先创建并取得服务端供应商状态');
  const changed = await runControlled({
    owner: 'Procurement.Supplier',
    objectId: supplier.supplierId,
    currentState: String(supplierResult.value.status || supplierResult.value.state),
    currentVersion: Number(supplierResult.value.version || 0),
    action: state,
    impact: '改变供应商可用状态，不改写历史采购单',
    reason: `经采购负责人确认变更为 ${state}`,
    execute: (key) => changeSupplierState(supplier.supplierId, { state, reason: `受控变更为 ${state}`, correlationId: key })
  });
  if (changed) supplierResult.value = changed;
};

const loadOrder = async () => {
  orderDetail.value = await runRead(() => getProcurementOrder(order.orderId));
};
const createOrder = async () => {
  const lineId = newOperationCommandId();
  const changed = await runControlled({
    owner: 'Procurement.Order',
    objectId: order.orderId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '创建采购商业快照；此动作本身不改变库存和成本',
    reason: '根据审批前采购计划创建草稿',
    execute: (key) =>
      createProcurementOrder({
        orderId: order.orderId,
        supplierId: supplier.supplierId,
        storeId: order.storeId,
        warehouseId: order.warehouseId,
        expectedDate: order.expectedDate,
        overReceiptToleranceBps: 0,
        lines: [
          {
            orderLineId: lineId,
            skuId: order.skuId,
            unitId: order.unitId,
            orderedQuantity: exactDecimal(order.quantity),
            unitPriceMinor: order.unitPriceMinor,
            taxRateBps: 0
          }
        ],
        correlationId: key
      })
  });
  if (changed) orderDetail.value = changed;
};
const orderAction = async (action: 'submit' | 'approve' | 'close') => {
  if (!orderDetail.value) return ElMessage.warning('请先读取采购单状态');
  const changed = await runControlled({
    owner: 'Procurement.Order',
    objectId: order.orderId,
    currentState: orderDetail.value.head.status,
    currentVersion: orderDetail.value.head.version,
    action: action.toUpperCase(),
    impact: '推进采购职责链；采购单状态本身不直接变更库存',
    reason: '按采购审批职责链执行',
    execute: (key) =>
      transitionProcurementOrder(order.orderId, action, { correlationId: key, ...(action === 'close' ? { reason: '采购业务已结清' } : {}) })
  });
  if (changed) orderDetail.value = changed;
};

const loadReceipt = async () => {
  receiptDetail.value = await runRead(() => getProcurementReceipt(receipt.receiptId));
};
const createReceipt = async () => {
  if (!orderDetail.value?.lines[0]) return ElMessage.warning('采购单至少需要一行');
  const changed = await runControlled({
    owner: 'Procurement.Receipt',
    objectId: receipt.receiptId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '创建原采购单收货草稿，尚不产生库存效果',
    reason: '按到货单录入收货草稿',
    execute: (key) =>
      createProcurementReceipt(order.orderId, {
        receiptId: receipt.receiptId,
        lines: [
          {
            receiptLineId: newOperationCommandId(),
            orderLineId: orderDetail.value!.lines[0].orderLineId,
            receivedQuantity: exactDecimal(receipt.quantity)
          }
        ],
        correlationId: key
      })
  });
  if (changed) receiptDetail.value = changed;
};
const confirmReceipt = async () => {
  if (!receiptDetail.value) return ElMessage.warning('请先读取收货状态');
  const changed = await runControlled({
    owner: 'Procurement.Receipt',
    objectId: receipt.receiptId,
    currentState: receiptDetail.value.head.status,
    currentVersion: receiptDetail.value.head.version,
    action: 'CONFIRM',
    impact: 'Inventory/Costing Owner 追加收货数量与成本流水',
    reason: '到货数量和原采购价复核通过',
    execute: (key) => confirmProcurementReceipt(receipt.receiptId, key, key)
  });
  if (changed) receiptDetail.value = changed;
};

const createReturn = async () => {
  if (!receiptDetail.value?.lines[0]) return ElMessage.warning('请先读取原收货行');
  const changed = await runControlled({
    owner: 'Procurement.Return',
    objectId: purchaseReturn.purchaseReturnId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '创建关联原收货的退货草稿，尚不产生库存效果',
    reason: '原收货商品经复核需要退回',
    execute: (key) =>
      createProcurementReturn(receipt.receiptId, {
        purchaseReturnId: purchaseReturn.purchaseReturnId,
        lines: [
          {
            returnLineId: newOperationCommandId(),
            receiptLineId: receiptDetail.value!.lines[0].receiptLineId,
            returnQuantity: exactDecimal(purchaseReturn.quantity)
          }
        ],
        reason: '不符合采购验收标准',
        correlationId: key
      })
  });
  if (changed) purchaseReturn.result = changed;
};
const returnAction = async (action: 'submit' | 'approve') => {
  if (!purchaseReturn.result) return ElMessage.warning('请先创建退货并取得服务端状态');
  const changed = await runControlled({
    owner: 'Procurement.Return',
    objectId: purchaseReturn.purchaseReturnId,
    currentState: String(purchaseReturn.result.status || purchaseReturn.result.state),
    currentVersion: Number(purchaseReturn.result.version || 0),
    action: action.toUpperCase(),
    impact: action === 'approve' ? 'Inventory/Costing Owner 追加采购退货冲减流水' : '提交采购退货审批，不直接改余额',
    reason: '按采购退货责任链执行',
    execute: (key) =>
      transitionProcurementReturn(purchaseReturn.purchaseReturnId, action, { correlationId: key, ...(action === 'approve' ? { eventId: key } : {}) })
  });
  if (changed) purchaseReturn.result = changed;
};

const loadTransfer = async () => {
  transferDetail.value = await runRead(() => getTransfer(transfer.transferId));
};
const createNewTransfer = async () => {
  const changed = await runControlled({
    owner: 'Transfer',
    objectId: transfer.transferId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '创建调拨申请，不同时覆盖两个仓库余额',
    reason: '门店间基础调拨申请',
    execute: (key) =>
      createTransfer({
        transferId: transfer.transferId,
        sourceStoreId: transfer.sourceStoreId,
        sourceWarehouseId: transfer.sourceWarehouseId,
        destinationStoreId: transfer.destinationStoreId,
        destinationWarehouseId: transfer.destinationWarehouseId,
        lines: [
          {
            transferLineId: newOperationCommandId(),
            skuId: transfer.skuId,
            unitId: transfer.unitId,
            requestedQuantity: exactDecimal(transfer.quantity)
          }
        ],
        reason: '门店补货调拨',
        correlationId: key
      })
  });
  if (changed) transferDetail.value = changed;
};
const transferAction = async (action: 'submit' | 'approve' | 'cancel') => {
  if (!transferDetail.value) return ElMessage.warning('请先读取调拨状态和版本');
  const changed = await runControlled({
    owner: 'Transfer',
    objectId: transfer.transferId,
    currentState: transferDetail.value.head.status,
    currentVersion: transferDetail.value.head.version,
    action: action.toUpperCase(),
    impact: '推进调拨责任链，不覆盖来源或目的余额',
    reason: '按调拨审批职责链执行',
    execute: (key) =>
      transitionTransfer(transfer.transferId, action, {
        commandId: key,
        expectedVersion: transferDetail.value!.head.version,
        reason: '受控调拨状态操作',
        correlationId: key
      })
  });
  if (changed) transferDetail.value = changed;
};
const dispatch = async () => {
  if (!transferDetail.value) return;
  const changed = await runControlled({
    owner: 'Transfer',
    objectId: transfer.transferId,
    currentState: transferDetail.value.head.status,
    currentVersion: transferDetail.value.head.version,
    action: 'DISPATCH',
    impact: '来源 Inventory/Costing Owner 追加发出流水并冻结成本快照',
    reason: '仓库复核后确认发出',
    execute: (key) =>
      dispatchTransfer(transfer.transferId, {
        dispatchId: key,
        eventId: key,
        expectedVersion: transferDetail.value!.head.version,
        correlationId: key
      })
  });
  if (changed) transferDetail.value = changed;
};
const receive = async () => {
  if (!transferDetail.value?.lines[0]) return;
  const changed = await runControlled({
    owner: 'Transfer',
    objectId: transfer.transferId,
    currentState: transferDetail.value.head.status,
    currentVersion: transferDetail.value.head.version,
    action: 'RECEIVE',
    impact: '目的 Inventory/Costing Owner 追加收货流水并继承来源成本快照',
    reason: '目的仓复核后确认收货',
    execute: (key) =>
      receiveTransfer(transfer.transferId, {
        receiptId: key,
        eventId: key,
        expectedVersion: transferDetail.value!.head.version,
        finalReceipt: transfer.finalReceipt,
        lines: [
          {
            receiptLineId: newOperationCommandId(),
            transferLineId: transferDetail.value!.lines[0].transferLineId,
            receivedQuantity: exactDecimal(transfer.operationQuantity)
          }
        ],
        correlationId: key
      })
  });
  if (changed) transferDetail.value = changed;
};
</script>

<style scoped>
.id-input {
  width: 280px;
}
.medium-input {
  width: 220px;
}
.small-input {
  width: 120px;
}
</style>
