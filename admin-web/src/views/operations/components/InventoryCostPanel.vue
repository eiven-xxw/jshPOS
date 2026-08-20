<template>
  <div class="operation-panel">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="库存与成本只读投影"
      description="数量、成本和历史流水均来自正式 Owner；重建只校验并重建可丢弃投影，不允许覆盖历史流水。"
    />

    <el-card class="mt-3" shadow="never">
      <template #header><span>库存 / 成本余额与不可变流水</span></template>
      <el-form :inline="true" label-width="90px">
        <el-form-item label="仓库 ULID"><el-input v-model="query.warehouseId" class="id-input" /></el-form-item>
        <el-form-item label="SKU ID"><el-input v-model="query.skuId" class="small-input" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['inventory:balance:read']" type="primary" :loading="busy" @click="loadInventory">查询库存</el-button>
          <el-button v-hasPermi="['inventory:cost-balance:read']" type="primary" plain :loading="busy" @click="loadCost">查询成本</el-button>
          <el-button v-hasPermi="['inventory:rebuild']" type="warning" plain @click="rebuildInventory">重建库存投影</el-button>
          <el-button v-hasPermi="['inventory:cost-rebuild']" type="warning" plain @click="rebuildCost">重建成本投影</el-button>
        </el-form-item>
      </el-form>

      <el-row :gutter="12">
        <el-col :span="12">
          <el-descriptions v-if="inventoryBalance" :column="2" border title="库存余额（可重建）">
            <el-descriptions-item label="在手">{{ inventoryBalance.onHandQuantity }}</el-descriptions-item>
            <el-descriptions-item label="预占">{{ inventoryBalance.reservedQuantity }}</el-descriptions-item>
            <el-descriptions-item label="冻结">{{ inventoryBalance.frozenQuantity }}</el-descriptions-item>
            <el-descriptions-item label="安全库存">{{ inventoryBalance.safetyStockQuantity }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ inventoryBalance.stockStatus }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ inventoryBalance.recordVersion }}</el-descriptions-item>
          </el-descriptions>
        </el-col>
        <el-col :span="12">
          <el-descriptions v-if="costBalance" :column="2" border title="成本余额（可重建）">
            <el-descriptions-item label="成本数量">{{ costBalance.costQuantity }}</el-descriptions-item>
            <el-descriptions-item label="成本金额（分）">{{ costBalance.costAmountMinor }}</el-descriptions-item>
            <el-descriptions-item label="平均单位成本">{{ costBalance.averageUnitCostMinor }}</el-descriptions-item>
            <el-descriptions-item label="币种">{{ costBalance.currencyCode }}</el-descriptions-item>
            <el-descriptions-item label="末流水序号">{{ costBalance.lastCostLedgerSequence }}</el-descriptions-item>
            <el-descriptions-item label="版本">{{ costBalance.recordVersion }}</el-descriptions-item>
          </el-descriptions>
        </el-col>
      </el-row>
      <el-tabs class="mt-3">
        <el-tab-pane label="库存流水">
          <el-table :data="inventoryLedger" border max-height="280">
            <el-table-column prop="ledgerSequence" label="序号" width="80" />
            <el-table-column prop="movementType" label="类型" width="150" />
            <el-table-column prop="quantityBefore" label="变更前" />
            <el-table-column prop="quantityDelta" label="变化" />
            <el-table-column prop="quantityAfter" label="变更后" />
            <el-table-column prop="sourceType" label="来源" />
            <el-table-column prop="sourceId" label="来源 ID" min-width="220" />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="成本流水">
          <el-table :data="costLedger" border max-height="280">
            <el-table-column prop="costLedgerSequence" label="序号" width="80" />
            <el-table-column prop="movementType" label="类型" width="150" />
            <el-table-column prop="quantityDelta" label="数量变化" />
            <el-table-column prop="costAmountDeltaMinor" label="成本变化（分）" />
            <el-table-column prop="unitCostMinor" label="单位成本" />
            <el-table-column prop="costEstimated" label="估计" width="70" />
            <el-table-column prop="sourceId" label="来源 ID" min-width="220" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-card class="mt-3" shadow="never">
      <template #header><span>动态盘点受控流程</span></template>
      <el-form :inline="true" label-width="95px">
        <el-form-item label="盘点 ULID"><el-input v-model="stocktakeId" class="id-input" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['inventory:stocktake:read']" @click="loadStocktake">读取盘点</el-button>
          <el-button v-hasPermi="['inventory:stocktake:create']" type="primary" @click="createNewStocktake">创建盘点</el-button>
        </el-form-item>
        <el-form-item label="SKU 列表"><el-input v-model="stocktakeDraft.skuIds" placeholder="101,102" class="medium-input" /></el-form-item>
        <el-form-item label="盲盘"><el-switch v-model="stocktakeDraft.blindCount" /></el-form-item>
        <el-form-item label="复盘阈值"><el-input v-model="stocktakeDraft.recountThreshold" class="small-input" /></el-form-item>
      </el-form>
      <el-descriptions v-if="stocktake" :column="4" border>
        <el-descriptions-item label="状态">{{ stocktake.head.status }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ stocktake.head.version }}</el-descriptions-item>
        <el-descriptions-item label="仓库">{{ stocktake.head.warehouseId }}</el-descriptions-item>
        <el-descriptions-item label="关联标识">{{ stocktake.head.correlationId }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="stocktake" :data="stocktake.lines" border class="mt-3" max-height="260" @current-change="selectStocktakeLine">
        <el-table-column prop="lineId" label="盘点行" min-width="220" />
        <el-table-column prop="skuId" label="SKU" />
        <el-table-column prop="snapshotQuantity" label="账面快照" />
        <el-table-column prop="countedQuantity" label="实盘" />
        <el-table-column prop="varianceQuantity" label="差异" />
        <el-table-column prop="countRevision" label="计数版本" />
      </el-table>
      <el-form v-if="stocktake" :inline="true" class="mt-3">
        <el-form-item label="选中行"><el-input v-model="countDraft.lineId" class="id-input" /></el-form-item>
        <el-form-item label="实盘数量"><el-input v-model="countDraft.quantity" class="small-input" /></el-form-item>
        <el-form-item label="设备"><el-input v-model="countDraft.deviceId" class="medium-input" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['inventory:stocktake:count']" @click="recordCount">保存计数</el-button>
          <el-button v-hasPermi="['inventory:stocktake:submit']" @click="stocktakeAction('submit')">提交复核</el-button>
          <el-button v-hasPermi="['inventory:stocktake:review']" type="warning" @click="stocktakeAction('review')">接受差异</el-button>
          <el-button v-hasPermi="['inventory:stocktake:approve']" type="danger" @click="stocktakeAction('approve')">审批入账</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  createStocktake,
  getCostBalance,
  getCostLedger,
  getInventoryBalance,
  getInventoryLedger,
  getStocktake,
  newOperationCommandId,
  rebuildCostBalance,
  rebuildInventoryBalance,
  recordStocktakeCount,
  transitionStocktake
} from '@/api/operations';
import type {
  CostBalanceView,
  CostLedgerView,
  InventoryBalanceView,
  InventoryLedgerView,
  StocktakeDetail,
  StocktakeLineView
} from '@/api/operations/types';
import { exactDecimal, parseSafePlatformIds } from '../model';
import { useControlledOperation } from '../useControlledOperation';

const { pageState, runRead, runControlled } = useControlledOperation();
const busy = computed(() => ['LOADING', 'SUBMITTING'].includes(pageState.value));
const query = reactive({ warehouseId: '', skuId: '' });
const inventoryBalance = ref<InventoryBalanceView>();
const inventoryLedger = ref<InventoryLedgerView[]>([]);
const costBalance = ref<CostBalanceView>();
const costLedger = ref<CostLedgerView[]>([]);
const stocktakeId = ref('');
const stocktake = ref<StocktakeDetail>();
const stocktakeDraft = reactive({ skuIds: '', blindCount: true, recountThreshold: '0' });
const countDraft = reactive({ lineId: '', quantity: '0', deviceId: 'SYNTHETIC-ADMIN-01' });

const loadInventory = async () => {
  inventoryBalance.value = await runRead(() => getInventoryBalance(query.warehouseId, query.skuId));
  inventoryLedger.value = await runRead(() => getInventoryLedger(query.warehouseId, query.skuId));
};

const loadCost = async () => {
  costBalance.value = await runRead(() => getCostBalance(query.warehouseId, query.skuId));
  costLedger.value = await runRead(() => getCostLedger(query.warehouseId, query.skuId));
};

const rebuildInventory = async () => {
  if (!inventoryBalance.value) return ElMessage.warning('请先读取服务端库存状态和版本');
  await runControlled({
    owner: 'Inventory',
    objectId: inventoryBalance.value.dimensionKey,
    currentState: inventoryBalance.value.stockStatus,
    currentVersion: inventoryBalance.value.recordVersion,
    action: 'REBUILD_BALANCE',
    impact: '从不可变库存流水重建可丢弃余额投影',
    reason: '受控核对库存投影',
    execute: (key) => rebuildInventoryBalance(query.warehouseId, query.skuId, key)
  });
  await loadInventory();
};

const rebuildCost = async () => {
  if (!costBalance.value) return ElMessage.warning('请先读取服务端成本状态和版本');
  await runControlled({
    owner: 'Costing',
    objectId: costBalance.value.costDimensionKey,
    currentState: 'PROJECTED',
    currentVersion: costBalance.value.recordVersion,
    action: 'REBUILD_COST_BALANCE',
    impact: '从不可变成本流水重建可丢弃成本投影',
    reason: '受控核对成本投影',
    execute: (key) => rebuildCostBalance(query.warehouseId, query.skuId, key, key)
  });
  await loadCost();
};

const loadStocktake = async () => {
  stocktake.value = await runRead(() => getStocktake(stocktakeId.value));
  query.warehouseId ||= stocktake.value.head.warehouseId;
};

const createNewStocktake = async () => {
  const id = stocktakeId.value || newOperationCommandId();
  stocktakeId.value = id;
  const changed = await runControlled({
    owner: 'Inventory.Stocktake',
    objectId: id,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '冻结账面快照和盘点范围；尚不产生库存调整流水',
    reason: '根据受权盘点计划创建动态盘点',
    execute: (key) =>
      createStocktake({
        stocktakeId: id,
        warehouseId: query.warehouseId,
        skuIds: parseSafePlatformIds(stocktakeDraft.skuIds),
        blindCount: stocktakeDraft.blindCount,
        recountThreshold: exactDecimal(stocktakeDraft.recountThreshold),
        correlationId: key
      })
  });
  if (changed) stocktake.value = changed;
};

const selectStocktakeLine = (line?: StocktakeLineView) => {
  if (line) countDraft.lineId = line.lineId;
};

const recordCount = async () => {
  if (!stocktake.value) return;
  const head = stocktake.value.head;
  const changed = await runControlled({
    owner: 'Inventory.Stocktake',
    objectId: countDraft.lineId,
    currentState: head.status,
    currentVersion: head.version,
    action: 'RECORD_COUNT',
    impact: '追加本次计数事实，不覆盖既有计数和库存余额',
    reason: '后台受权录入盘点计数',
    execute: (key) =>
      recordStocktakeCount(head.stocktakeId, countDraft.lineId, {
        countId: key,
        countedQuantity: exactDecimal(countDraft.quantity),
        deviceId: countDraft.deviceId,
        reason: '后台受权录入盘点计数',
        correlationId: key
      })
  });
  if (changed) stocktake.value = changed;
};

const stocktakeAction = async (action: 'submit' | 'review' | 'approve') => {
  if (!stocktake.value) return;
  const head = stocktake.value.head;
  const changed = await runControlled({
    owner: 'Inventory.Stocktake',
    objectId: head.stocktakeId,
    currentState: head.status,
    currentVersion: head.version,
    action: action.toUpperCase(),
    impact: action === 'approve' ? '追加盘盈盘亏流水并更新可重建投影' : '推进盘点责任链但不直接覆盖余额',
    reason: action === 'review' ? '复核计数与截止流水后接受差异' : '按盘点职责链执行受控操作',
    execute: (key) =>
      transitionStocktake(
        head.stocktakeId,
        action,
        action === 'review'
          ? { decision: 'ACCEPT', reason: '复核差异并接受', correlationId: key }
          : action === 'approve'
            ? { eventId: key, correlationId: key }
            : { correlationId: key }
      )
  });
  if (changed) stocktake.value = changed;
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
