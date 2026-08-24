<template>
  <div class="p-4 lot-expiry-page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="批次与效期仅适用于社区超市模板"
      description="便利店和零食折扣店默认关闭。页面不计算 FEFO、库存或成本；所有状态、余额和预警均来自 Inventory/Catalog Owner。批次成本、复杂 WMS 和真实设备不在本功能范围。"
    />
    <OwnerPageFeedback surface-id="VUE-14" :state="phase" :failure="pageFailure" @retry="reloadCurrent" />

    <el-card class="mt-3" shadow="never">
      <template #header><span>1. 发布不可变批次策略版本</span></template>
      <el-form :model="policy" :inline="true" label-width="110px">
        <el-form-item label="门店"><el-input-number v-model="policy.storeId" data-testid="lot-store" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="SKU"><el-input-number v-model="policy.skuId" data-testid="lot-sku" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="策略版本 ULID"><el-input v-model="policy.policyVersionId" data-testid="lot-version" class="ulid-input" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="policy.enabled" /></el-form-item>
        <el-form-item label="日期基准">
          <el-select v-model="policy.expiryBasis" class="basis-select">
            <el-option label="显式到期日" value="EXPLICIT_EXPIRY_DATE" />
            <el-option label="生产日期 + 保质期" value="PRODUCTION_DATE" />
            <el-option label="入库日期 + 保质期" value="RECEIVED_DATE" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="policy.expiryBasis !== 'EXPLICIT_EXPIRY_DATE'" label="保质期天数">
          <el-input-number v-model="policy.shelfLifeDays" :min="1" :max="36500" />
        </el-form-item>
        <el-form-item label="临期天数"><el-input-number v-model="policy.nearExpiryDays" :min="0" :max="3650" /></el-form-item>
        <el-form-item label="生效时间"><el-date-picker v-model="effectiveAt" type="datetime" /></el-form-item>
        <el-form-item>
          <el-button
            v-hasPermi="['catalog:lot-policy:publish']"
            data-testid="lot-policy-publish"
            type="primary"
            :loading="loading"
            @click="publishPolicy"
          >
            发布策略
          </el-button>
          <el-button v-hasPermi="['catalog:lot-policy:read']" data-testid="lot-policy-read" :loading="loading" @click="loadPolicy"
            >读取当前策略</el-button
          >
        </el-form-item>
      </el-form>
      <el-descriptions v-if="currentPolicy" :column="4" border>
        <el-descriptions-item label="策略版本">{{ currentPolicy.policyVersionId }}</el-descriptions-item>
        <el-descriptions-item label="行业">社区超市</el-descriptions-item>
        <el-descriptions-item label="状态">{{ currentPolicy.state }}</el-descriptions-item>
        <el-descriptions-item label="启用">{{ currentPolicy.enabled ? '是' : '否' }}</el-descriptions-item>
        <el-descriptions-item label="内容摘要" :span="4"
          ><code>{{ currentPolicy.contentSha256 }}</code></el-descriptions-item
        >
      </el-descriptions>
    </el-card>

    <el-card class="mt-3" shadow="never">
      <template #header><span>2. 临期与过期库存</span></template>
      <el-form :inline="true">
        <el-form-item label="门店"><el-input-number v-model="query.storeId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="仓库 ULID"><el-input v-model="query.warehouseId" class="ulid-input" /></el-form-item>
        <el-form-item label="业务日"><el-date-picker v-model="businessDate" type="date" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['inventory:lot:read']" type="primary" :loading="loading" @click="loadAlerts">查询</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="alerts" border row-key="lotId" max-height="520">
        <el-table-column prop="lotId" label="批次 ULID" min-width="240" />
        <el-table-column prop="skuId" label="SKU" width="110" />
        <el-table-column prop="supplierLotCode" label="供应商批号" min-width="150" />
        <el-table-column prop="internalLotCode" label="内部批号" min-width="150" />
        <el-table-column prop="productionDate" label="生产日期" width="120" />
        <el-table-column prop="receivedDate" label="入库日期" width="120" />
        <el-table-column prop="expiryDate" label="到期日" width="120" />
        <el-table-column prop="nearExpiryDays" label="临期阈值(天)" width="120" />
        <el-table-column prop="onHandQuantity" label="批次在手" width="120" />
        <el-table-column label="状态" width="120">
          <template #default="scope">
            <el-tag :type="scope.row.expiryStatus === 'EXPIRED' ? 'danger' : 'warning'">{{ scope.row.expiryStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="policyVersionId" label="冻结策略版本" min-width="240" />
        <el-table-column prop="lastLedgerSequence" label="流水序号" width="110" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { getEffectiveLotPolicy, listLotExpiryAlerts, publishLotPolicy } from '@/api/lot-expiry';
import type { ExpiryBasis, LotPolicyView, LotView, PublishLotPolicyCommand } from '@/api/lot-expiry/types';
import { newOperationCommandId } from '@/api/operations';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import OwnerPageFeedback from '../components/OwnerPageFeedback.vue';

const { phase, failure: pageFailure, runRead, runWrite } = useRecoverablePage('LOT_EXPIRY_PAGE_FAILED');

const policy = reactive<{
  policyVersionId: string;
  storeId?: number;
  skuId?: number;
  enabled: boolean;
  expiryBasis: ExpiryBasis;
  shelfLifeDays?: number;
  nearExpiryDays: number;
}>({ policyVersionId: '', enabled: true, expiryBasis: 'EXPLICIT_EXPIRY_DATE', nearExpiryDays: 3 });
const query = reactive<{ storeId?: number; warehouseId: string }>({ warehouseId: '' });
const effectiveAt = ref(new Date());
const businessDate = ref(new Date());
const currentPolicy = ref<LotPolicyView>();
const alerts = ref<LotView[]>([]);
const loading = computed(() => phase.value === 'LOADING' || phase.value === 'SUBMITTING');
const commandKeys = new Map<string, string>();
const lastReadMode = ref<'policy' | 'alerts'>('policy');

const commandIdentity = (action: string): string => {
  const mapKey = `${policy.policyVersionId || policy.storeId || 'none'}:${action}`;
  if (!commandKeys.has(mapKey)) commandKeys.set(mapKey, newOperationCommandId());
  return commandKeys.get(mapKey)!;
};
const read = async <T,>(work: () => Promise<{ data: T }>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => {
  const response = await runRead(work, (value) => empty(value.data));
  return response?.data;
};
const write = async <T,>(operationIdentity: string, work: () => Promise<{ data: T }>): Promise<T | undefined> => {
  const response = await runWrite(operationIdentity, work);
  return response?.data;
};
const isoDate = (value: Date) => value.toISOString().slice(0, 10);

const publishPolicy = async () => {
  if (!policy.storeId || !policy.skuId || !policy.policyVersionId) return ElMessage.warning('门店、SKU 和策略版本不能为空');
  const command: PublishLotPolicyCommand = {
    policyVersionId: policy.policyVersionId,
    storeId: policy.storeId,
    skuId: policy.skuId,
    enabled: policy.enabled,
    expiryBasis: policy.expiryBasis,
    shelfLifeDays: policy.expiryBasis === 'EXPLICIT_EXPIRY_DATE' ? undefined : policy.shelfLifeDays,
    nearExpiryDays: policy.nearExpiryDays,
    effectiveFrom: effectiveAt.value.toISOString()
  };
  await ElMessageBox.confirm(
    `门店：${policy.storeId}；SKU：${policy.skuId}；策略版本：${policy.policyVersionId}；发布后不可原地修改。确认继续？`,
    '发布批次效期策略',
    { type: 'warning' }
  );
  const operationIdentity = commandIdentity('publish');
  const result = await write(operationIdentity, () => publishLotPolicy(command, operationIdentity));
  if (result) currentPolicy.value = result;
};

const loadPolicy = async () => {
  if (!policy.storeId || !policy.skuId) return ElMessage.warning('门店和 SKU 不能为空');
  lastReadMode.value = 'policy';
  const result = await read(() => getEffectiveLotPolicy(policy.storeId!, policy.skuId!, effectiveAt.value.toISOString()));
  if (result) currentPolicy.value = result;
};

const loadAlerts = async () => {
  if (!query.storeId || !query.warehouseId) return ElMessage.warning('门店和仓库不能为空');
  lastReadMode.value = 'alerts';
  const result = await read(
    () => listLotExpiryAlerts({ storeId: query.storeId!, warehouseId: query.warehouseId, businessDate: isoDate(businessDate.value), limit: 500 }),
    (value) => value.length === 0
  );
  if (result) alerts.value = result;
};
const reloadCurrent = () => (lastReadMode.value === 'alerts' ? loadAlerts() : loadPolicy());
</script>

<style scoped>
.ulid-input {
  width: 300px;
}
.basis-select {
  width: 210px;
}
code {
  overflow-wrap: anywhere;
}
</style>
