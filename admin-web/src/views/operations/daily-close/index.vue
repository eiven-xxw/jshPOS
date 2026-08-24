<template>
  <div class="p-4 daily-close-page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="门店日结只冻结并签署各 Owner 的权威事实，不会修改订单、支付、退款、库存、成本或报表"
      description="支付机构、真实硬件和打印仍为 BLOCKED/UNAVAILABLE；本工作台不会伪造渠道对账通过，也不会把报表值覆盖到业务事实。"
    />
    <OwnerPageFeedback surface-id="VUE-12" :state="phase" :failure="pageFailure" @retry="loadList" />

    <el-card class="mt-3" shadow="never">
      <template #header><span>1. 门店与业务日</span></template>
      <el-form :inline="true" label-width="100px">
        <el-form-item label="门店 ID"><el-input-number v-model="form.storeId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="业务日"><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item
          ><el-button v-hasPermi="['operations:daily-close:read']" data-testid="daily-close-read" :loading="loading" @click="loadList"
            >查询</el-button
          ></el-form-item
        >
        <el-form-item>
          <el-button v-hasPermi="['operations:daily-close:create']" type="primary" :loading="loading" @click="createClose">创建日结草稿</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="rows" border row-key="closeId" @row-click="selectRow">
        <el-table-column prop="businessDate" label="业务日" width="120" />
        <el-table-column prop="storeId" label="门店" width="100" />
        <el-table-column prop="closeVersion" label="版本" width="80" />
        <el-table-column label="状态" width="190">
          <template #default="scope"
            ><el-tag :type="stateTag(scope.row.state)">{{ scope.row.state }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="zoneId" label="时区" min-width="160" />
        <el-table-column prop="businessDayStart" label="日切" width="100" />
        <el-table-column prop="closeId" label="日结 ULID" min-width="260" />
      </el-table>
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>2. 预检、独立审批与只追加签署</span></template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="状态"
          ><el-tag :type="stateTag(detail.close.state)">{{ detail.close.state }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="门店 / 业务日">{{ detail.close.storeId }} / {{ detail.close.businessDate }}</el-descriptions-item>
        <el-descriptions-item label="日结版本">{{ detail.close.closeVersion }}</el-descriptions-item>
        <el-descriptions-item label="预检轮次">{{ detail.close.preflightRun || '-' }}</el-descriptions-item>
        <el-descriptions-item label="时区 / 日切">{{ detail.close.zoneId }} / {{ detail.close.businessDayStart }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ detail.close.creatorUserId }}</el-descriptions-item>
        <el-descriptions-item label="更正原版本">{{ detail.close.correctionOfCloseId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="需要更正"
          ><el-tag :type="detail.correctionRequired ? 'danger' : 'success'">{{
            detail.correctionRequired ? '是' : '否'
          }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="事实快照摘要" :span="4"
          ><code>{{ detail.close.snapshotSha256 }}</code></el-descriptions-item
        >
        <el-descriptions-item label="来源清单摘要" :span="4"
          ><code>{{ detail.close.manifestSha256 }}</code></el-descriptions-item
        >
      </el-descriptions>
      <el-form :inline="true" class="mt-3">
        <el-form-item label="审批原因"><el-input v-model="reason" class="reason-input" maxlength="256" show-word-limit /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['operations:daily-close:preflight']" :loading="loading" @click="runAction('preflight')">完整预检</el-button>
          <el-button v-hasPermi="['operations:daily-close:approve']" type="warning" :loading="loading" @click="runAction('approve')"
            >独立审批</el-button
          >
          <el-button v-hasPermi="['operations:daily-close:sign']" type="success" :loading="loading" @click="confirmSign">签署并关闭</el-button>
          <el-button v-hasPermi="['operations:daily-close:late-fact']" type="danger" plain :loading="loading" @click="runAction('late-facts')"
            >扫描晚到事实</el-button
          >
        </el-form-item>
      </el-form>
      <el-alert class="mt-2" type="info" :closable="false" title="创建人与审批/签署人必须分离；CLOSED 后只能新增差异和更正版本，不能重开或覆盖。" />
    </el-card>

    <el-card v-if="latestSnapshot" class="mt-3" shadow="never">
      <template #header><span>3. 冻结金额快照（最小货币单位）</span></template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="订单 / 取消 / 退货"
          >{{ latestSnapshot.orderCount }} / {{ latestSnapshot.cancelledOrderCount }} / {{ latestSnapshot.returnCount }}</el-descriptions-item
        >
        <el-descriptions-item label="Gross">{{ latestSnapshot.grossMinor }} {{ latestSnapshot.currency }}</el-descriptions-item>
        <el-descriptions-item label="Discount">{{ latestSnapshot.discountMinor }}</el-descriptions-item>
        <el-descriptions-item label="Surcharge">{{ latestSnapshot.surchargeMinor }}</el-descriptions-item>
        <el-descriptions-item label="Receivable">{{ latestSnapshot.receivableMinor }}</el-descriptions-item>
        <el-descriptions-item label="退款">{{ latestSnapshot.refundMinor }}</el-descriptions-item>
        <el-descriptions-item label="电子收款">{{ latestSnapshot.electronicReceivedMinor }}</el-descriptions-item>
        <el-descriptions-item label="电子退款">{{ latestSnapshot.electronicRefundedMinor }}</el-descriptions-item>
        <el-descriptions-item label="UNKNOWN支付/退款">
          {{ latestSnapshot.unknownPaymentCount }}/{{ latestSnapshot.unknownRefundCount }}
        </el-descriptions-item>
        <el-descriptions-item label="现金实收 / 退回"
          >{{ latestSnapshot.cashReceivedMinor }} / {{ latestSnapshot.cashRefundedMinor }}</el-descriptions-item
        >
        <el-descriptions-item label="班次差异">{{ latestSnapshot.shiftDifferenceMinor }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>4. Owner 检查点与预检证据</span></template>
      <el-table :data="detail.checkpoints" border max-height="260">
        <el-table-column prop="runNo" label="轮次" width="75" />
        <el-table-column prop="ownerCode" label="Owner" min-width="150" />
        <el-table-column prop="sourceVersion" label="来源版本" min-width="190" />
        <el-table-column prop="sourceSequence" label="序号" width="100" />
        <el-table-column prop="sourceStatus" label="状态" width="130" />
        <el-table-column prop="contentSha256" label="内容摘要" min-width="280" show-overflow-tooltip />
      </el-table>
      <el-table :data="detail.preflights" border class="mt-3" max-height="420">
        <el-table-column prop="runNo" label="轮次" width="75" />
        <el-table-column prop="checkCode" label="检查项" min-width="230" />
        <el-table-column prop="ownerCode" label="Owner" width="150" />
        <el-table-column label="证据边界" width="120">
          <template #default="scope"
            ><el-tag :type="scope.row.external ? 'warning' : 'info'">{{ scope.row.external ? '外部 P0' : '内部事实' }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="结果" width="120">
          <template #default="scope"
            ><el-tag :type="checkTag(scope.row.status)">{{ scope.row.status }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="maskedMessage" label="脱敏说明" min-width="320" />
      </el-table>
    </el-card>

    <el-card v-if="detail && detail.differences.length" class="mt-3" shadow="never">
      <template #header><span>5. 差异与晚到事实（只追加）</span></template>
      <el-table :data="detail.differences" border>
        <el-table-column prop="type" label="差异类型" min-width="260" />
        <el-table-column prop="state" label="状态" width="100" />
        <el-table-column prop="detectedAt" label="检出时间" width="190" />
        <el-table-column prop="detailSha256" label="差异摘要" min-width="280" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  approveDailyClose,
  createDailyClose,
  detectDailyCloseLateFacts,
  getDailyClose,
  listDailyCloses,
  preflightDailyClose,
  signDailyClose
} from '@/api/daily-close';
import type { DailyCloseCheckStatus, DailyCloseDetail, DailyCloseRecord, DailyCloseState } from '@/api/daily-close/types';
import { newOperationCommandId } from '@/api/operations';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import OwnerPageFeedback from '../components/OwnerPageFeedback.vue';

const { phase, failure: pageFailure, runRead, runWrite } = useRecoverablePage('DAILY_CLOSE_PAGE_FAILED');

const form = reactive<{ storeId?: number; businessDate: string }>({ businessDate: new Date().toISOString().slice(0, 10) });
const rows = ref<DailyCloseRecord[]>([]);
const detail = ref<DailyCloseDetail>();
const reason = ref('已复核权威事实、差异、权限和外部阻断边界');
const loading = computed(() => phase.value === 'LOADING' || phase.value === 'SUBMITTING');
const identities = new Map<string, string>();

const latestSnapshot = computed(() => detail.value?.snapshots.at(-1));

const identity = (action: string) => {
  const base = `${detail.value?.close.closeId ?? 'new'}:${action}`;
  if (!identities.has(base)) identities.set(base, newOperationCommandId());
  const value = identities.get(base)!;
  return { idempotencyKey: value, correlationId: value };
};

const read = async <T,>(work: () => Promise<{ data: T }>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => {
  const response = await runRead(work, (value) => empty(value.data));
  return response?.data;
};
const write = async <T,>(operationIdentity: string, work: () => Promise<{ data: T }>): Promise<T | undefined> => {
  const response = await runWrite(operationIdentity, work);
  return response?.data;
};

const loadList = async () => {
  if (!form.storeId) return ElMessage.warning('请先选择有权访问的门店');
  const result = await read(
    () => listDailyCloses({ storeId: form.storeId!, businessDate: form.businessDate, limit: 100 }),
    (value) => value.length === 0
  );
  if (result) rows.value = result;
};

const createClose = async () => {
  if (!form.storeId || !form.businessDate) return ElMessage.warning('门店与业务日不能为空');
  const requestIdentity = identity('create');
  const result = await write(requestIdentity.idempotencyKey, () =>
    createDailyClose({ storeId: form.storeId!, businessDate: form.businessDate }, requestIdentity)
  );
  if (!result) return;
  detail.value = result;
  await loadList();
};

const selectRow = async (row: DailyCloseRecord) => {
  const result = await read(() => getDailyClose(row.closeId));
  if (result) detail.value = result;
};

const runAction = async (action: 'preflight' | 'approve' | 'late-facts') => {
  if (!detail.value) return;
  const id = detail.value.close.closeId;
  if (action === 'approve' || action === 'late-facts') {
    await ElMessageBox.confirm(
      action === 'approve'
        ? '审批人必须独立于创建人，且不会改写来源事实。确认继续？'
        : '扫描只会追加晚到差异和更正要求，不会覆盖已签署日结。确认继续？',
      action === 'approve' ? '确认独立审批' : '确认扫描晚到事实',
      { type: 'warning' }
    );
  }
  const requestIdentity = identity(action);
  const calls = {
    preflight: () => preflightDailyClose(id, requestIdentity),
    approve: () => approveDailyClose(id, reason.value, requestIdentity),
    'late-facts': () => detectDailyCloseLateFacts(id, requestIdentity)
  };
  const result = await write(requestIdentity.idempotencyKey, calls[action]);
  if (!result) return;
  detail.value = result;
  await loadList();
};

const confirmSign = async () => {
  if (!detail.value) return;
  await ElMessageBox.confirm('签署会形成不可覆盖的 CLOSED 事实；来源摘要变化将失败关闭。确认继续？', '只追加日结签署', {
    type: 'warning',
    confirmButtonText: '确认签署',
    cancelButtonText: '取消'
  });
  const requestIdentity = identity('sign');
  const result = await write(requestIdentity.idempotencyKey, () => signDailyClose(detail.value!.close.closeId, requestIdentity));
  if (!result) return;
  detail.value = result;
  await loadList();
};

const stateTag = (value: DailyCloseState) => {
  if (value === 'CLOSED') return 'success';
  if (['FAILED', 'PREFLIGHT_FAILED', 'COMPENSATION_REQUIRED', 'CORRECTION_REQUIRED'].includes(value)) return 'danger';
  if (value === 'READY' || value === 'APPROVED' || value === 'CLOSING') return 'warning';
  return 'info';
};

const checkTag = (value: DailyCloseCheckStatus) => {
  if (value === 'PASS') return 'success';
  if (value === 'FAIL') return 'danger';
  if (value === 'BLOCKED' || value === 'UNAVAILABLE') return 'warning';
  return 'info';
};

onMounted(loadList);
</script>

<style scoped>
.reason-input {
  width: 480px;
}
code {
  overflow-wrap: anywhere;
}
</style>
