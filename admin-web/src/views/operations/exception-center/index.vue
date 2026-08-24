<template>
  <div class="p-4 exception-center-page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="异常中心只编排 Owner 修复，不重算或覆盖资金、库存、成本、报表与日结事实"
      description="支付 Provider、真实设备和打印仍为 BLOCKED/UNAVAILABLE；UNKNOWN 只能观察原命令，页面不会生成新的扣款或退款。"
    />
    <OwnerPageFeedback surface-id="VUE-13" :state="phase" :failure="pageFailure" @retry="load" />
    <el-card class="mt-3" shadow="never">
      <template #header><span>1. 可信门店异常队列</span></template>
      <el-form :inline="true">
        <el-form-item label="门店 ID"><el-input-number v-model="form.storeId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="业务日"><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态"
          ><el-select v-model="form.state" clearable class="filter"><el-option v-for="s in states" :key="s" :label="s" :value="s" /></el-select
        ></el-form-item>
        <el-form-item label="级别"
          ><el-select v-model="form.severity" clearable class="filter"
            ><el-option v-for="s in ['P0', 'P1', 'P2', 'P3']" :key="s" :label="s" :value="s" /></el-select
        ></el-form-item>
        <el-form-item
          ><el-button v-hasPermi="['operations:exception:read']" data-testid="exception-read" :loading="loading" @click="load">查询</el-button>
          <el-button v-hasPermi="['operations:exception:scan']" type="primary" :loading="loading" @click="scan">扫描 Owner</el-button></el-form-item
        >
      </el-form>
      <el-table :data="rows" border row-key="caseId" @row-click="select">
        <el-table-column prop="severity" label="级别" width="70"
          ><template #default="s"
            ><el-tag :type="s.row.severity === 'P0' ? 'danger' : 'warning'">{{ s.row.severity }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="state" label="状态" width="150"
          ><template #default="s"
            ><el-tag :type="tag(s.row.state)">{{ s.row.state }}</el-tag></template
          ></el-table-column
        >
        <el-table-column prop="sourceOwner" label="Owner" width="150" /><el-table-column prop="sourceType" label="异常类型" min-width="220" />
        <el-table-column prop="assigneeUserId" label="认领人" width="100" /><el-table-column prop="leaseExpiresAt" label="租约到期" width="190" />
        <el-table-column prop="lastObservedAt" label="最近观察" width="190" /><el-table-column prop="caseId" label="案件 ULID" min-width="260" />
      </el-table>
    </el-card>
    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>2. 来源血缘与受控处置</span></template>
      <el-descriptions :column="3" border
        ><el-descriptions-item label="Owner/类型"
          >{{ detail.exceptionCase.sourceOwner }} / {{ detail.exceptionCase.sourceType }}</el-descriptions-item
        >
        <el-descriptions-item label="来源事实">{{ detail.exceptionCase.sourceFactId }}</el-descriptions-item
        ><el-descriptions-item label="序号">{{ detail.exceptionCase.latestSourceSequence }}</el-descriptions-item>
        <el-descriptions-item label="最新摘要" :span="3"
          ><code>{{ detail.exceptionCase.latestSourceSha256 }}</code></el-descriptions-item
        ></el-descriptions
      >
      <el-form :inline="true" class="mt-3"
        ><el-form-item label="租约分钟"><el-input-number v-model="form.leaseMinutes" :min="5" :max="120" /></el-form-item>
        <el-form-item label="转派员工"><el-input-number v-model="form.assigneeUserId" :min="1" :controls="false" /></el-form-item>
        <el-form-item label="Owner 动作"><el-input v-model="form.actionCode" class="action-input" /></el-form-item
        ><el-form-item label="原因/计划"><el-input v-model="form.reason" class="reason-input" maxlength="256" /></el-form-item
      ></el-form>
      <div class="actions">
        <el-button v-hasPermi="['operations:exception:claim']" @click="run('claim')">认领</el-button
        ><el-button v-hasPermi="['operations:exception:operate']" @click="run('transfer')">转派</el-button
        ><el-button v-hasPermi="['operations:exception:operate']" @click="run('start')">开始处置</el-button>
        <el-button v-hasPermi="['operations:exception:operate']" type="warning" @click="run('plan')">保存计划</el-button
        ><el-button v-hasPermi="['operations:exception:repair']" type="danger" @click="run('repair')">调用 Owner 修复</el-button>
        <el-button v-hasPermi="['operations:exception:review']" type="success" @click="run('review')">独立复核</el-button
        ><el-button v-hasPermi="['operations:exception:close']" @click="run('close')">关闭</el-button>
        <el-button v-hasPermi="['operations:exception:close']" plain @click="run('reopen')">重开</el-button>
      </div>
    </el-card>
    <el-card v-if="detail" class="mt-3" shadow="never"
      ><template #header><span>3. 观察、修复与审计时间线</span></template>
      <el-table :data="detail.observations" border
        ><el-table-column prop="observedAt" label="观察时间" width="190" /><el-table-column prop="sourceEventId" label="来源事件" min-width="210" />
        <el-table-column prop="conflictFlag" label="内容关系" width="190" /><el-table-column prop="maskedSummary" label="去敏摘要" min-width="260"
      /></el-table>
      <el-table :data="detail.repairs" border class="mt-3"
        ><el-table-column prop="requestedAt" label="请求时间" width="190" /><el-table-column prop="actionCode" label="Owner动作" min-width="210" />
        <el-table-column prop="state" label="结果" width="150" /><el-table-column prop="ownerResultReference" label="结果引用" min-width="220"
      /></el-table>
      <el-timeline class="mt-3"
        ><el-timeline-item v-for="s in detail.states" :key="s.stateEventId" :timestamp="s.occurredAt"
          >{{ s.fromState || 'CREATED' }} → {{ s.toState }}（操作者 {{ s.actorUserId }}）</el-timeline-item
        ></el-timeline
      >
      <el-table :data="detail.audits" border class="mt-3"
        ><el-table-column prop="occurredAt" label="审计时间" width="190" /><el-table-column prop="actionCode" label="动作" min-width="180" />
        <el-table-column prop="resultCode" label="结果" width="120" /><el-table-column prop="actorUserId" label="操作者" width="120" />
        <el-table-column prop="correlationId" label="关联标识" min-width="220"
      /></el-table>
    </el-card>
  </div>
</template>
<script setup lang="ts">
import {
  claimExceptionCase,
  closeExceptionCase,
  executeExceptionRepair,
  getExceptionCase,
  listExceptionCases,
  planExceptionRepair,
  reopenExceptionCase,
  reviewExceptionCase,
  scanExceptionOwners,
  startExceptionCase,
  transferExceptionCase
} from '@/api/exception-center';
import type { ExceptionCaseDetail, ExceptionCaseRecord, ExceptionCaseState } from '@/api/exception-center/types';
import { newOperationCommandId } from '@/api/operations';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import OwnerPageFeedback from '../components/OwnerPageFeedback.vue';

const { phase, failure: pageFailure, runRead, runWrite } = useRecoverablePage('EXCEPTION_PAGE_FAILED');
const states: ExceptionCaseState[] = ['OPEN', 'CLAIMED', 'IN_PROGRESS', 'WAITING_OWNER', 'RESOLVED', 'CLOSED', 'REOPENED', 'FAILED'];
const form = reactive<{
  storeId?: number;
  businessDate: string;
  state?: string;
  severity?: string;
  leaseMinutes: number;
  assigneeUserId?: number;
  actionCode: string;
  reason: string;
}>({
  businessDate: new Date().toISOString().slice(0, 10),
  leaseMinutes: 30,
  actionCode: 'MANUAL_OWNER_REVIEW',
  reason: '已核验来源血缘、Owner结果和失败关闭边界'
});
const rows = ref<ExceptionCaseRecord[]>([]);
const detail = ref<ExceptionCaseDetail>();
const loading = computed(() => phase.value === 'LOADING' || phase.value === 'SUBMITTING');
const identities = new Map<string, string>();
const identity = (action: string) => {
  const objectId = detail.value?.exceptionCase.caseId ?? `${form.storeId ?? 'none'}:${form.businessDate}`;
  const mapKey = `${objectId}:${action}`;
  if (!identities.has(mapKey)) identities.set(mapKey, newOperationCommandId());
  const id = identities.get(mapKey)!;
  return { idempotencyKey: id, correlationId: id };
};
const read = async <T,>(work: () => Promise<{ data: T }>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => {
  const response = await runRead(work, (value) => empty(value.data));
  return response?.data;
};
const write = async <T,>(operationIdentity: string, work: () => Promise<{ data: T }>): Promise<T | undefined> => {
  const response = await runWrite(operationIdentity, work);
  return response?.data;
};
const load = async () => {
  if (!form.storeId) return ElMessage.warning('请选择有权访问的门店');
  const result = await read(
    () => listExceptionCases({ storeId: form.storeId!, state: form.state, severity: form.severity, limit: 100 }),
    (value) => value.length === 0
  );
  if (result) rows.value = result;
};
const scan = async () => {
  if (!form.storeId) return ElMessage.warning('请选择门店');
  const requestIdentity = identity('scan');
  const result = await write(requestIdentity.idempotencyKey, () =>
    scanExceptionOwners({ storeId: form.storeId!, businessDate: form.businessDate }, requestIdentity)
  );
  if (result) rows.value = result;
};
const select = async (row: ExceptionCaseRecord) => {
  const result = await read(() => getExceptionCase(row.caseId));
  if (result) detail.value = result;
};
const run = async (name: 'claim' | 'transfer' | 'start' | 'plan' | 'repair' | 'review' | 'close' | 'reopen') => {
  if (!detail.value) return;
  const id = detail.value.exceptionCase.caseId;
  const i = identity(name);
  const calls = {
    claim: () => claimExceptionCase(id, form.leaseMinutes, i),
    transfer: () => {
      if (!form.assigneeUserId) throw new Error('请输入目标员工 ID');
      return transferExceptionCase(id, form.assigneeUserId, form.leaseMinutes, form.reason, i);
    },
    start: () => startExceptionCase(id, form.reason, i),
    plan: () => planExceptionRepair(id, form.actionCode, form.reason, i),
    repair: () => executeExceptionRepair(id, form.actionCode, i),
    review: () => reviewExceptionCase(id, form.reason, i),
    close: () => closeExceptionCase(id, form.reason, i),
    reopen: () => reopenExceptionCase(id, form.reason, i)
  };
  if (['transfer', 'plan', 'repair', 'review', 'close', 'reopen'].includes(name))
    await ElMessageBox.confirm(
      `案件：${id}；动作：${name}；只保存修复命令引用和结果摘要，不覆盖来源事实。确认继续？`,
      '受控异常操作',
      { type: 'warning' }
    );
  const result = await write(i.idempotencyKey, calls[name]);
  if (!result) return;
  detail.value = result;
  await load();
};
const tag = (s: ExceptionCaseState) =>
  s === 'CLOSED' ? 'success' : ['FAILED', 'REOPENED'].includes(s) ? 'danger' : ['WAITING_OWNER', 'CLAIMED'].includes(s) ? 'warning' : 'info';
onMounted(load);
</script>
<style scoped>
.filter {
  width: 170px;
}
.action-input {
  width: 240px;
}
.reason-input {
  width: 420px;
}
.actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
code {
  overflow-wrap: anywhere;
}
</style>
