<template>
  <div>
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="确定性补货不会自动下单"
      description="建议数量、原因、库存检查点和单位换算均由服务端冻结；审批后也只创建采购 DRAFT，不产生库存或成本效果。"
    />
    <OwnerPageFeedback surface-id="VUE-10" :state="pageState" :failure="pageFailure" @retry="reloadCurrent" />
    <el-card shadow="never" class="mt-3">
      <template #header><span>补货规则</span></template>
      <el-form :inline="true">
        <el-form-item label="规则版本"><el-input v-model="form.policyVersionId" class="id-input" /></el-form-item>
        <el-form-item label="门店"><el-input v-model="form.storeId" class="small-input" /></el-form-item>
        <el-form-item label="仓库"><el-input v-model="form.warehouseId" class="id-input" /></el-form-item>
        <el-form-item label="SKU/采购单位"
          ><el-input v-model="form.skuId" class="small-input" /> / <el-input v-model="form.purchaseUnitId" class="small-input"
        /></el-form-item>
        <el-form-item label="供应商"><el-input v-model="form.supplierId" class="id-input" /></el-form-item>
        <el-form-item label="最低/最高"
          ><el-input v-model="form.minimum" class="small-input" /> / <el-input v-model="form.maximum" class="small-input"
        /></el-form-item>
        <el-form-item label="最小量/倍数"
          ><el-input v-model="form.minimumOrder" class="small-input" /> / <el-input v-model="form.multiple" class="small-input"
        /></el-form-item>
        <el-form-item label="抵扣确认在途"><el-switch v-model="form.includeTransit" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['procurement:replenishment:policy']" type="primary" @click="createPolicy">创建规则</el-button>
          <el-button v-hasPermi="['procurement:replenishment:policy']" type="warning" @click="publishPolicy">发布</el-button>
          <el-button
            v-hasPermi="['procurement:replenishment:read']"
            data-testid="replenishment-policy-read"
            :disabled="submitting"
            @click="loadPolicies"
            >刷新</el-button
          >
        </el-form-item>
      </el-form>
      <el-table :data="policies" border max-height="220">
        <el-table-column prop="policyVersionId" label="规则版本" min-width="220" />
        <el-table-column prop="warehouseId" label="仓库" min-width="220" />
        <el-table-column prop="versionNo" label="业务版本" width="100" />
        <el-table-column prop="state" label="状态" width="120" />
        <el-table-column prop="version" label="记录版本" width="100" />
      </el-table>
    </el-card>

    <el-card shadow="never" class="mt-3">
      <template #header><span>缺货预警与补货建议</span></template>
      <el-button v-hasPermi="['procurement:replenishment:generate']" type="primary" @click="generate">按当前权威检查点生成</el-button>
      <el-button v-hasPermi="['procurement:replenishment:read']" @click="loadSuggestions">刷新建议</el-button>
      <el-table :data="suggestions" border class="mt-3" max-height="360">
        <el-table-column prop="skuCode" label="SKU" min-width="120" />
        <el-table-column prop="availableQuantity" label="可用" />
        <el-table-column prop="confirmedInTransitQuantity" label="确认在途" />
        <el-table-column prop="minimumBaseQuantity" label="下限" />
        <el-table-column prop="maximumBaseQuantity" label="上限" />
        <el-table-column prop="suggestedPurchaseQuantity" label="建议采购" />
        <el-table-column prop="reasonCode" label="解释" min-width="220" />
        <el-table-column prop="state" label="状态" width="150" />
        <el-table-column label="受控操作" min-width="300">
          <template #default="scope">
            <el-button
              v-if="scope.row.state === 'GENERATED'"
              v-hasPermi="['procurement:replenishment:review']"
              @click="suggestionAction(scope.row, 'review')"
              >复核</el-button
            >
            <el-button
              v-if="scope.row.state === 'REVIEWED'"
              v-hasPermi="['procurement:replenishment:approve']"
              type="warning"
              @click="suggestionAction(scope.row, 'approve')"
              >审批</el-button
            >
            <el-button
              v-if="['GENERATED', 'REVIEWED', 'APPROVED'].includes(scope.row.state)"
              v-hasPermi="['procurement:replenishment:review']"
              type="danger"
              @click="suggestionAction(scope.row, 'reject')"
              >驳回</el-button
            >
            <el-button
              v-if="scope.row.state === 'APPROVED'"
              v-hasPermi="['procurement:replenishment:draft']"
              type="primary"
              @click="toDraft(scope.row)"
              >转采购草稿</el-button
            >
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  createReplenishmentPolicy,
  createReplenishmentPurchaseDraft,
  generateReplenishmentSuggestions,
  listReplenishmentPolicies,
  listReplenishmentSuggestions,
  newOperationCommandId,
  transitionReplenishmentPolicy,
  transitionReplenishmentSuggestion
} from '@/api/operations';
import type { ReplenishmentPolicy, ReplenishmentSuggestion } from '@/api/operations/types';
import { exactDecimal } from '../model';
import { useControlledOperation } from '../useControlledOperation';
import OwnerPageFeedback from './OwnerPageFeedback.vue';

const { pageState, pageFailure, submitting, runRead, runControlled } = useControlledOperation();
const form = reactive({
  policyVersionId: newOperationCommandId(),
  policyItemId: newOperationCommandId(),
  generationRunId: newOperationCommandId(),
  storeId: '1101',
  warehouseId: '01J00000000000000000000011',
  skuId: '101',
  purchaseUnitId: '1',
  supplierId: '01J00000000000000000000021',
  minimum: '5.000000',
  maximum: '20.000000',
  minimumOrder: '1.000000',
  multiple: '1.000000',
  includeTransit: true
});
const policies = ref<ReplenishmentPolicy[]>([]);
const suggestions = ref<ReplenishmentSuggestion[]>([]);
const lastReadMode = ref<'policies' | 'suggestions'>('policies');

const loadPolicies = async () => {
  lastReadMode.value = 'policies';
  const result = await runRead(() => listReplenishmentPolicies(form.storeId), (value) => value.length === 0);
  if (result) policies.value = result;
};
const loadSuggestions = async () => {
  lastReadMode.value = 'suggestions';
  const result = await runRead(() => listReplenishmentSuggestions(form.storeId), (value) => value.length === 0);
  if (result) suggestions.value = result;
};
const reloadCurrent = () => (lastReadMode.value === 'suggestions' ? loadSuggestions() : loadPolicies());
const createPolicy = async () => {
  const changed = await runControlled({
    owner: 'Replenishment.Policy',
    objectId: form.policyVersionId,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE',
    impact: '冻结最低/最高库存、采购单位、供应商、最小量和倍数；不改变库存或采购承诺',
    reason: '经门店补货负责人确认创建规则草稿',
    execute: (key) =>
      createReplenishmentPolicy({
        policyVersionId: form.policyVersionId,
        storeId: form.storeId,
        warehouseId: form.warehouseId,
        versionNo: 1,
        effectiveFrom: new Date().toISOString(),
        idempotencyKey: key,
        correlationId: key,
        items: [
          {
            policyItemId: form.policyItemId,
            skuId: form.skuId,
            purchaseUnitId: form.purchaseUnitId,
            supplierId: form.supplierId,
            minimumBaseQuantity: exactDecimal(form.minimum),
            maximumBaseQuantity: exactDecimal(form.maximum),
            minimumOrderQuantity: exactDecimal(form.minimumOrder),
            orderMultiple: exactDecimal(form.multiple),
            includeConfirmedInTransit: form.includeTransit,
            unitPriceMinor: '0',
            taxRateBps: 0
          }
        ]
      })
  });
  if (changed) await loadPolicies();
};
const publishPolicy = async () => {
  const policy = policies.value.find((item) => item.policyVersionId === form.policyVersionId);
  if (!policy) return ElMessage.warning('请先读取服务端规则状态');
  const changed = await runControlled({
    owner: 'Replenishment.Policy',
    objectId: policy.policyVersionId,
    currentState: policy.state,
    currentVersion: policy.version,
    action: 'PUBLISH',
    impact: '发布不可变规则版本，后续建议仍以库存检查点计算',
    reason: '规则参数已复核',
    execute: (key) =>
      transitionReplenishmentPolicy(policy.policyVersionId, 'publish', {
        expectedVersion: policy.version,
        idempotencyKey: key,
        reason: '规则参数已复核',
        correlationId: key
      })
  });
  if (changed) await loadPolicies();
};
const generate = async () => {
  const changed = await runControlled({
    owner: 'Replenishment.Generation',
    objectId: form.policyVersionId,
    currentState: 'PUBLISHED',
    currentVersion: 0,
    action: 'GENERATE',
    impact: '只读取权威快照并生成可解释建议，不自动下单',
    reason: '执行本次人工补货检查',
    execute: (key) =>
      generateReplenishmentSuggestions({
        generationRunId: form.generationRunId,
        policyVersionId: form.policyVersionId,
        calculationAt: new Date().toISOString(),
        idempotencyKey: key,
        correlationId: key
      })
  });
  if (changed) suggestions.value = changed.suggestions;
};
const suggestionAction = async (item: ReplenishmentSuggestion, action: 'review' | 'approve' | 'reject') => {
  const changed = await runControlled({
    owner: 'Replenishment.Suggestion',
    objectId: item.suggestionId,
    currentState: item.state,
    currentVersion: item.version,
    action: action.toUpperCase(),
    impact: '只推进建议责任链，不修改库存或采购单',
    reason: '补货建议输入与解释已人工复核',
    execute: (key) =>
      transitionReplenishmentSuggestion(item.suggestionId, action, {
        expectedVersion: item.version,
        idempotencyKey: key,
        reason: '补货建议输入与解释已人工复核',
        correlationId: key
      })
  });
  if (changed) await loadSuggestions();
};
const toDraft = async (item: ReplenishmentSuggestion) => {
  const changed = await runControlled({
    owner: 'Replenishment.Suggestion',
    objectId: item.suggestionId,
    currentState: item.state,
    currentVersion: item.version,
    action: 'PURCHASE_DRAFT',
    impact: '只创建采购 DRAFT；审批、收货、库存和成本效果均为零',
    reason: '审批后人工决定转采购草稿',
    execute: (key) =>
      createReplenishmentPurchaseDraft(item.suggestionId, {
        expectedVersion: item.version,
        purchaseOrderId: key,
        expectedDate: new Date().toISOString().slice(0, 10),
        idempotencyKey: key,
        correlationId: key
      })
  });
  if (changed) await loadSuggestions();
};
</script>

<style scoped>
.id-input {
  width: 280px;
}
.small-input {
  width: 120px;
}
</style>
