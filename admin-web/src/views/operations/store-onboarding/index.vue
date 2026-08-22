<template>
  <div class="p-4 store-onboarding-page">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="门店开通采用白名单复制、版本化行业模板、独立审批和权威事实检查"
      description="订单、支付、退款、库存、成本、会员、审计及同步历史不会被复制。支付、硬件、打印和设计伙伴未解阻时只显示 BLOCKED/UNAVAILABLE，绝不伪造通过。"
    />

    <el-card class="mt-3" shadow="never">
      <template #header><span>1. 创建或读取开店计划</span></template>
      <el-form :inline="true" label-width="110px">
        <el-form-item label="来源门店（可空）">
          <el-input-number v-model="form.sourceStoreId" :min="1" :controls="false" placeholder="仅模板开店可留空" />
        </el-form-item>
        <el-form-item label="目标门店">
          <el-input-number v-model="form.targetStoreId" :min="1" :controls="false" />
        </el-form-item>
        <el-form-item label="行业模板">
          <el-input-number v-model="form.templateId" :min="1" :controls="false" />
        </el-form-item>
        <el-form-item label="模板版本">
          <el-input-number v-model="form.templateVersionId" :min="1" :controls="false" />
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['onboarding:plan:create']" type="primary" :loading="loading" @click="createPlan"> 创建冻结计划 </el-button>
        </el-form-item>
      </el-form>
      <el-form :inline="true">
        <el-form-item label="计划 ULID"><el-input v-model="planId" class="plan-id" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['onboarding:plan:read']" :loading="loading" @click="refresh">读取计划</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>2. 冻结版本、状态与受控推进</span></template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="计划状态"
          ><el-tag :type="stateTag(detail.plan.state)">{{ detail.plan.state }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="业态">{{ industryName(detail.plan.industry) }}</el-descriptions-item>
        <el-descriptions-item label="来源 / 目标">{{ detail.plan.sourceStoreId ?? '仅模板' }} / {{ detail.plan.targetStoreId }}</el-descriptions-item>
        <el-descriptions-item label="记录版本">{{ detail.plan.recordVersion }}</el-descriptions-item>
        <el-descriptions-item label="模板版本">{{ detail.plan.templateVersionNo }}（{{ detail.plan.templateVersionId }}）</el-descriptions-item>
        <el-descriptions-item label="来源版本">{{ detail.plan.sourceStoreVersion ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标版本">{{ detail.plan.targetStoreVersion }}</el-descriptions-item>
        <el-descriptions-item label="检查轮次">{{ detail.plan.checkRun || '-' }}</el-descriptions-item>
        <el-descriptions-item label="配置快照摘要" :span="4"
          ><code>{{ detail.plan.snapshotSha256 }}</code></el-descriptions-item
        >
      </el-descriptions>

      <el-form :inline="true" class="mt-3">
        <el-form-item label="审批/操作原因">
          <el-input v-model="reason" class="reason-input" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['onboarding:plan:preflight']" :loading="loading" @click="runAction('preflight')">完整预检</el-button>
          <el-button v-hasPermi="['onboarding:plan:approve']" type="warning" :loading="loading" @click="runAction('approve')">独立审批</el-button>
          <el-button v-hasPermi="['onboarding:plan:apply']" type="primary" :loading="loading" @click="runAction('apply')">应用冻结配置</el-button>
          <el-button v-hasPermi="['onboarding:plan:check']" type="primary" plain :loading="loading" @click="runAction('checks')"
            >执行开店检查</el-button
          >
          <el-button v-hasPermi="['onboarding:plan:open']" type="success" :loading="loading" @click="confirmOpen">确认开店</el-button>
          <el-button v-hasPermi="['onboarding:plan:cancel']" type="danger" plain :loading="loading" @click="runAction('cancel')"
            >取消未应用计划</el-button
          >
        </el-form-item>
      </el-form>
      <el-alert class="mt-2" type="info" :closable="false" title="READY_TO_OPEN 仅表示内部软件检查通过；外部必需项未 PASS 时服务端仍禁止 OPENED。" />
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>3. 白名单配置与 Owner 检查点</span></template>
      <el-table :data="detail.snapshot" border max-height="260">
        <el-table-column prop="itemKey" label="批准的配置键" min-width="200" />
        <el-table-column prop="contentJson" label="冻结内容" min-width="280" show-overflow-tooltip />
        <el-table-column prop="contentSha256" label="内容摘要" min-width="260" show-overflow-tooltip />
      </el-table>
      <el-table :data="detail.checkpoints" border class="mt-3" max-height="220">
        <el-table-column prop="stepCode" label="Owner 步骤" min-width="220" />
        <el-table-column prop="state" label="状态" width="130" />
        <el-table-column prop="resultSha256" label="稳定结果摘要" min-width="280" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="完成时间" min-width="190" />
      </el-table>
      <el-table :data="detail.approvals" border class="mt-3" max-height="180">
        <el-table-column prop="approverUserId" label="审批人" width="130" />
        <el-table-column prop="reason" label="审批原因" min-width="260" />
        <el-table-column prop="approvedAt" label="审批时间" min-width="190" />
      </el-table>
    </el-card>

    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header><span>4. 开店检查与外部阻断</span></template>
      <el-table :data="detail.checks" border max-height="420" row-key="checkId">
        <el-table-column prop="checkCode" label="检查项" min-width="190" />
        <el-table-column prop="ownerType" label="权威 Owner" width="130" />
        <el-table-column label="证据边界" width="130">
          <template #default="scope"
            ><el-tag :type="scope.row.external ? 'warning' : 'info'">{{ scope.row.external ? '外部 P0' : '内部事实' }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="状态" width="135">
          <template #default="scope"
            ><el-tag :type="checkTag(scope.row.status)">{{ scope.row.status }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="factVersion" label="事实版本" min-width="150" />
        <el-table-column prop="maskedMessage" label="脱敏说明" min-width="300" />
        <el-table-column prop="factSha256" label="事实摘要" min-width="260" show-overflow-tooltip />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  applyOnboardingPlan,
  approveOnboardingPlan,
  cancelOnboardingPlan,
  checkOnboardingPlan,
  createOnboardingPlan,
  getOnboardingPlan,
  openOnboardingStore,
  preflightOnboardingPlan
} from '@/api/onboarding';
import type { OnboardingCheckStatus, OnboardingPlanDetail, OnboardingState } from '@/api/onboarding/types';
import { newOperationCommandId } from '@/api/operations';

const form = reactive<{ sourceStoreId?: number; targetStoreId?: number; templateId?: number; templateVersionId?: number }>({});
const planId = ref('');
const detail = ref<OnboardingPlanDetail>();
const reason = ref('已复核冻结版本、权限、检查结果和外部阻断边界');
const loading = ref(false);
const identities = new Map<string, string>();

const identity = (action: string) => {
  const key = `${planId.value}:${action}`;
  if (!identities.has(key)) identities.set(key, newOperationCommandId());
  const value = identities.get(key)!;
  return { idempotencyKey: value, correlationId: value };
};

const execute = async (work: () => Promise<unknown>) => {
  loading.value = true;
  try {
    return await work();
  } finally {
    loading.value = false;
  }
};

const createPlan = async () => {
  if (!form.targetStoreId || !form.templateId || !form.templateVersionId) {
    return ElMessage.warning('目标门店、行业模板和模板版本不能为空');
  }
  const commandId = newOperationCommandId();
  const created = (await execute(() =>
    createOnboardingPlan(
      {
        sourceStoreId: form.sourceStoreId,
        targetStoreId: form.targetStoreId!,
        templateId: form.templateId!,
        templateVersionId: form.templateVersionId!
      },
      { idempotencyKey: commandId, correlationId: commandId }
    )
  )) as OnboardingPlanDetail;
  planId.value = created.plan.planId;
  detail.value = created;
};

const refresh = async () => {
  if (!planId.value) return ElMessage.warning('请输入或创建开店计划');
  detail.value = (await execute(() => getOnboardingPlan(planId.value))) as OnboardingPlanDetail;
};

const runAction = async (action: 'preflight' | 'approve' | 'apply' | 'checks' | 'cancel') => {
  if (!planId.value) return ElMessage.warning('请先读取开店计划');
  const request = identity(action);
  const calls = {
    preflight: () => preflightOnboardingPlan(planId.value, request),
    approve: () => approveOnboardingPlan(planId.value, reason.value, request),
    apply: () => applyOnboardingPlan(planId.value, request),
    checks: () => checkOnboardingPlan(planId.value, request),
    cancel: () => cancelOnboardingPlan(planId.value, reason.value, request)
  };
  detail.value = (await execute(calls[action])) as OnboardingPlanDetail;
};

const confirmOpen = async () => {
  await ElMessageBox.confirm('只有全部内部和外部必需检查 PASS 才能形成 OPENED。确认继续？', '确认开店', {
    type: 'warning',
    confirmButtonText: '确认',
    cancelButtonText: '取消'
  });
  detail.value = (await execute(() => openOnboardingStore(planId.value, reason.value, identity('open')))) as OnboardingPlanDetail;
};

const industryName = (value: string) =>
  ({
    CONVENIENCE: '便利店',
    SNACK_DISCOUNT: '零食折扣店',
    COMMUNITY_SUPERMARKET: '社区超市'
  })[value] ?? value;

const stateTag = (value: OnboardingState) => {
  if (value === 'OPENED') return 'success';
  if (['FAILED', 'PREFLIGHT_FAILED', 'CHECK_FAILED', 'COMPENSATION_REQUIRED'].includes(value)) return 'danger';
  if (value === 'READY_TO_OPEN' || value === 'READY' || value === 'APPROVED') return 'warning';
  return 'info';
};

const checkTag = (value: OnboardingCheckStatus) => {
  if (value === 'PASS') return 'success';
  if (value === 'FAIL') return 'danger';
  if (value === 'BLOCKED' || value === 'UNAVAILABLE') return 'warning';
  return 'info';
};
</script>

<style scoped>
.plan-id {
  width: 330px;
}
.reason-input {
  width: 480px;
}
code {
  overflow-wrap: anywhere;
}
</style>
