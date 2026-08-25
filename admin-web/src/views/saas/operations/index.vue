<template>
  <div class="p-4 saas-operations">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="Gate 8A SaaS 商户运营（内部软件证据）"
      description="tenant_id、状态、审批和配额均由服务端决定；本页面不执行真实收费、支付、设备、伙伴现场或生产开户。"
    />
    <OwnerPageFeedback
      surface-id="saas"
      :state="phase"
      :failure="failure"
      empty-title="当前平台数据范围内暂无商户申请"
      @retry="refresh"
    />

    <el-tabs v-model="activeTab" class="mt-3" type="border-card">
      <el-tab-pane label="套餐与权益" name="plan">
        <el-form :inline="true">
          <el-form-item label="套餐代码"><el-input v-model="plan.planCode" maxlength="64" /></el-form-item>
          <el-form-item label="套餐名称"><el-input v-model="plan.planName" maxlength="64" /></el-form-item>
          <el-form-item label="技术套餐 ID"><el-input-number v-model="plan.platformPackageId" :min="1" /></el-form-item>
          <el-form-item label="账号上限"><el-input-number v-model="plan.accountLimit" :min="1" /></el-form-item>
          <el-form-item
            ><el-button v-hasPermi="['saas:plan:create']" type="primary" :loading="loading" @click="savePlan">创建套餐</el-button></el-form-item
          >
        </el-form>
        <el-descriptions v-if="createdPlan" :column="4" border
          ><el-descriptions-item label="Plan ID">{{ createdPlan.planId }}</el-descriptions-item
          ><el-descriptions-item label="代码">{{ createdPlan.planCode }}</el-descriptions-item
          ><el-descriptions-item label="账号上限">{{ createdPlan.accountLimit }}</el-descriptions-item
          ><el-descriptions-item label="状态">{{ createdPlan.status }}</el-descriptions-item></el-descriptions
        >

        <el-divider>权益版本</el-divider>
        <el-form :inline="true">
          <el-form-item label="Plan ID"><el-input-number v-model="version.planId" :min="1" /></el-form-item>
          <el-form-item label="版本号"><el-input-number v-model="version.versionNo" :min="1" /></el-form-item>
          <el-form-item label="生效时间"
            ><el-date-picker v-model="version.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
          /></el-form-item>
          <el-form-item
            ><el-button v-hasPermi="['saas:entitlement:create']" :loading="loading" @click="saveVersion">创建权益版本</el-button></el-form-item
          >
        </el-form>
        <el-table :data="version.items" border
          ><el-table-column prop="featureCode" label="功能代码" /><el-table-column label="启用" width="100"
            ><template #default="scope"><el-switch v-model="scope.row.enabled" /></template></el-table-column
          ><el-table-column label="配额"
            ><template #default="scope"><el-input-number v-model="scope.row.quotaLimit" :min="0" /></template></el-table-column
        ></el-table>
        <el-form v-if="createdVersion" :inline="true" class="mt-3"
          ><el-form-item label="版本状态"
            ><el-tag>{{ createdVersion.state }}</el-tag></el-form-item
          ><el-form-item
            ><el-button
              v-for="action in entitlementActions"
              :key="action"
              v-hasPermi="['saas:entitlement:publish']"
              :loading="loading"
              @click="advanceVersion(action)"
              >{{ action }}</el-button
            ></el-form-item
          ></el-form
        >
      </el-tab-pane>

      <el-tab-pane label="商户开户" name="application">
        <el-form :inline="true"
          ><el-form-item label="申请号"><el-input v-model="application.applicationCode" /></el-form-item
          ><el-form-item label="企业名称"><el-input v-model="application.companyName" /></el-form-item
          ><el-form-item label="业态"
            ><el-select v-model="application.industry"
              ><el-option label="便利店" value="CONVENIENCE" /><el-option label="零食折扣" value="SNACK_DISCOUNT" /><el-option
                label="社区超市"
                value="COMMUNITY_SUPERMARKET" /></el-select></el-form-item
          ><el-form-item label="Plan ID"><el-input-number v-model="application.planId" :min="1" /></el-form-item
          ><el-form-item
            ><el-button v-hasPermi="['saas:application:create']" type="primary" :loading="loading" @click="createApplication"
              >创建申请</el-button
            ></el-form-item
          ></el-form
        >
        <el-form :inline="true"
          ><el-form-item label="申请 ULID"><el-input v-model="applicationId" class="wide" /></el-form-item
          ><el-form-item
            ><el-button v-hasPermi="['saas:application:read']" data-testid="saas-application-read" :loading="loading" @click="refresh">读取</el-button></el-form-item
          ></el-form
        >
        <el-descriptions v-if="detail" :column="4" border
          ><el-descriptions-item label="状态"
            ><el-tag>{{ detail.application.state }}</el-tag></el-descriptions-item
          ><el-descriptions-item label="租户">{{ detail.application.tenantId || '尚未分配' }}</el-descriptions-item
          ><el-descriptions-item label="套餐">{{ detail.application.planId }}</el-descriptions-item
          ><el-descriptions-item label="检查点">{{ detail.checkpoints.join('、') || '-' }}</el-descriptions-item
          ><el-descriptions-item label="内容摘要" :span="4"
            ><code>{{ detail.application.contentSha256 }}</code></el-descriptions-item
          ></el-descriptions
        >
        <el-form v-if="detail" :inline="true" class="mt-3"
          ><el-form-item label="审批原因"><el-input v-model="reason" class="wide" /></el-form-item
          ><el-form-item
            ><el-button v-hasPermi="['saas:application:preflight']" @click="appAction('preflight')">预检</el-button
            ><el-button v-hasPermi="['saas:application:approve']" type="warning" @click="appAction('approve')">独立审批</el-button
            ><el-button v-hasPermi="['saas:application:initialize']" @click="appAction('initialize')">初始化</el-button
            ><el-button v-hasPermi="['saas:application:activate']" type="success" @click="appAction('activate')">激活</el-button></el-form-item
          ></el-form
        >
        <el-card v-if="detail?.application.state === 'APPROVED'" class="mt-3" shadow="never"
          ><template #header>一次性技术开户凭据（提交后立即清空）</template
          ><el-form :inline="true"
            ><el-form-item label="联系人"><el-input v-model="provision.contactName" /></el-form-item
            ><el-form-item label="联系电话"><el-input v-model="provision.contactPhone" /></el-form-item
            ><el-form-item label="初始账号"><el-input v-model="provision.bootstrapUsername" /></el-form-item
            ><el-form-item label="一次性密码"><el-input v-model="provision.bootstrapPassword" type="password" show-password /></el-form-item
            ><el-form-item
              ><el-button v-hasPermi="['saas:application:provision']" type="danger" @click="provisionTenant"
                >创建停用态技术租户</el-button
              ></el-form-item
            ></el-form
          ></el-card
        >
      </el-tab-pane>

      <el-tab-pane label="生命周期" name="lifecycle">
        <el-alert
          type="info"
          :closable="false"
          title="暂停、停用和逻辑注销不会删除任何交易历史；受控退款、对账、审计、备份、导出、迁移和删除请求保持可用。"
        />
        <el-form :inline="true" class="mt-3"
          ><el-form-item label="租户号"><el-input v-model="lifecycleTenant" /></el-form-item
          ><el-form-item label="原因"><el-input v-model="reason" class="wide" /></el-form-item
          ><el-form-item
            ><el-button
              v-for="action in lifecycleActions"
              :key="action"
              :data-testid="`saas-lifecycle-${action}`"
              v-hasPermi="['saas:tenant:lifecycle']"
              :loading="loading"
              @click="runLifecycle(action)"
              >{{ action }}</el-button
            ></el-form-item
          ></el-form
        >
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ElMessageBox } from 'element-plus';
import OwnerPageFeedback from '@/views/operations/components/OwnerPageFeedback.vue';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import { useStableOperationIdentity } from '@/composables/useStableOperationIdentity';
import {
  activateSaasApplication,
  advanceEntitlementVersion,
  approveSaasApplication,
  changeTenantLifecycle,
  createEntitlementVersion,
  createSaasApplication,
  createSaasPlan,
  getSaasApplication,
  initializeSaasApplication,
  preflightSaasApplication,
  provisionSaasApplication
} from '@/api/saas';
import type { SaasIdentity } from '@/api/saas';
import type { EntitlementVersion, SaasApplicationDetail, SaasPlan } from '@/api/saas/types';
import { newOperationCommandId } from '@/api/operations';

const activeTab = ref('plan'),
  applicationId = ref(''),
  detail = ref<SaasApplicationDetail>(),
  createdPlan = ref<SaasPlan>(),
  createdVersion = ref<EntitlementVersion>(),
  reason = ref('已完成权限、版本、摘要和历史保留复核'),
  lifecycleTenant = ref('');
const plan = reactive({ planCode: 'V1_STANDARD', planName: '商业V1标准版', platformPackageId: 1, accountLimit: 50 });
const application = reactive({ applicationCode: 'SYNTHETIC-APPLICATION-001', companyName: '虚构演示商户', industry: 'CONVENIENCE', planId: 1 });
const provision = reactive({ contactName: '虚构联系人', contactPhone: '00000000000', bootstrapUsername: 'synthetic_admin', bootstrapPassword: '' });
const version = reactive({
  planId: 1,
  versionNo: 1,
  effectiveAt: new Date(Date.now() - 60_000).toISOString().slice(0, 19),
  items: [
    { featureCode: 'STORE_COUNT', enabled: true, quotaLimit: 10 },
    { featureCode: 'TERMINAL_COUNT', enabled: true, quotaLimit: 20 },
    { featureCode: 'SALE', enabled: true, quotaLimit: null as number | null }
  ]
});
const entitlementActions = ['validate', 'approve', 'publish', 'activate'] as const,
  lifecycleActions = ['suspend', 'deactivate', 'restore', 'request-termination', 'terminate-logical'] as const;
const { phase, failure, submitting, runRead, runWrite } = useRecoverablePage('SAAS_PAGE_FAILED');
const operationKeys = useStableOperationIdentity(newOperationCommandId);
const loading = computed(() => phase.value === 'LOADING' || submitting.value);
const identity = (action: string) => {
  const value = operationKeys.get(action);
  return { idempotencyKey: value, correlationId: value };
};
/** 同一次失败重试复用原命令，服务端确认成功后才为下一次业务操作生成新键。 */
const executeCommand = async <T,>(action: string, work: (commandIdentity: SaasIdentity) => Promise<T>) => {
  const commandIdentity = identity(action);
  const result = await runWrite(`saas:${action}`, () => work(commandIdentity));
  if (result !== undefined) operationKeys.complete(action);
  return result;
};
const run = async <T,>(work: () => Promise<T>) => {
  return runRead(work);
};
const confirmImpact = async (title: string, message: string): Promise<boolean> => {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' });
    return true;
  } catch {
    return false;
  }
};
const savePlan = async () => {
  const result = await executeCommand('plan:create', (i) => createSaasPlan(plan, i));
  if (!result) return;
  createdPlan.value = result.data;
  version.planId = createdPlan.value.planId;
  application.planId = createdPlan.value.planId;
};
const saveVersion = async () => {
  const result = await executeCommand('version:create', (i) =>
    createEntitlementVersion(version.planId, { versionNo: version.versionNo, effectiveAt: version.effectiveAt, items: version.items }, i)
  );
  if (result) createdVersion.value = result.data;
};
const advanceVersion = async (action: (typeof entitlementActions)[number]) => {
  if (!createdVersion.value) return;
  if (action !== 'validate' && !(await confirmImpact('套餐权益版本确认', `权益版本 ${createdVersion.value.versionId}；动作 ${action}；发布后内容不可原地修改。`))) return;
  const result = await executeCommand(`version:${action}`, (i) => advanceEntitlementVersion(createdVersion.value!.versionId, action, i));
  if (result) createdVersion.value = result.data;
};
const createApplication = async () => {
  const result = await executeCommand('application:create', (i) => createSaasApplication(application, i));
  if (!result) return;
  detail.value = result.data;
  applicationId.value = detail.value.application.applicationId;
};
const refresh = async () => {
  const result = await run(() => getSaasApplication(applicationId.value));
  if (!result) return;
  detail.value = result.data;
  lifecycleTenant.value = detail.value.application.tenantId || '';
};
const appAction = async (action: 'preflight' | 'approve' | 'initialize' | 'activate') => {
  if (action !== 'preflight' && !(await confirmImpact('商户开户状态确认', `申请 ${applicationId.value}；动作 ${action}；租户号只允许由服务端编排。`))) return;
  const result = await executeCommand(`application:${action}`, (i) =>
      action === 'preflight'
        ? preflightSaasApplication(applicationId.value, i)
        : action === 'approve'
          ? approveSaasApplication(applicationId.value, reason.value, i)
          : action === 'initialize'
            ? initializeSaasApplication(applicationId.value, i)
            : activateSaasApplication(applicationId.value, i)
    );
  if (result) detail.value = result.data;
};
const provisionTenant = async () => {
  try {
    if (!(await confirmImpact('创建停用态技术租户', `申请 ${applicationId.value}；初始账号 ${provision.bootstrapUsername}；一次性密码提交后立即清空。`))) return;
    const result = await executeCommand('application:provision', (i) => provisionSaasApplication(applicationId.value, { ...provision }, i));
    if (!result) return;
    detail.value = result.data;
    lifecycleTenant.value = detail.value.application.tenantId || '';
  } finally {
    provision.bootstrapPassword = '';
  }
};
const runLifecycle = async (action: (typeof lifecycleActions)[number]) => {
  if (!(await confirmImpact('租户生命周期确认', `租户 ${lifecycleTenant.value}；动作 ${action}；历史事实不会被删除，受控恢复入口保留。`))) return;
  const result = await executeCommand(`lifecycle:${action}`, (i) => changeTenantLifecycle(lifecycleTenant.value, action, reason.value, i));
  if (!result) return;
  await refresh();
};
</script>

<style scoped>
.saas-operations .wide {
  width: 360px;
}
.saas-operations code {
  word-break: break-all;
}
</style>
