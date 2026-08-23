<template>
  <div class="p-4 subscription-operations">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="Gate 8A 订阅运营（内部软件执行）"
      description="服务端决定期限、状态和访问模式；本页面不执行真实计费、扣款、支付 Provider、发票或资金结算。"
    />
    <el-card class="mt-3" shadow="never">
      <template #header>创建或读取订阅</template>
      <el-form :inline="true">
        <el-form-item label="目标租户"><el-input v-model="targetTenant" /></el-form-item>
        <el-form-item label="订阅 ULID"><el-input v-model="subscriptionId" class="wide" /></el-form-item>
        <el-form-item><el-button v-hasPermi="['subscription:read']" :loading="loading" @click="refresh">读取</el-button></el-form-item>
      </el-form>
      <el-form :inline="true">
        <el-form-item label="合同引用"><el-input v-model="term.contractRef" /></el-form-item>
        <el-form-item label="外部订单引用"><el-input v-model="term.externalOrderRef" /></el-form-item>
        <el-form-item label="开始"><el-date-picker v-model="term.startsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="结束"><el-date-picker v-model="term.endsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item label="宽限结束"><el-date-picker v-model="term.graceEndsAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item>
        <el-form-item
          ><el-button v-hasPermi="['subscription:create']" type="primary" :loading="loading" @click="create">创建草稿</el-button></el-form-item
        >
      </el-form>
    </el-card>
    <el-card v-if="detail" class="mt-3" shadow="never">
      <template #header>服务端权威订阅状态</template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="租户">{{ detail.subscription.tenantId }}</el-descriptions-item>
        <el-descriptions-item label="状态"
          ><el-tag>{{ detail.subscription.state }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="访问模式"
          ><el-tag type="warning">{{ detail.accessMode }}</el-tag></el-descriptions-item
        >
        <el-descriptions-item label="期限版本">{{ detail.subscription.currentTermVersion }}</el-descriptions-item>
        <el-descriptions-item label="恢复白名单" :span="4">{{
          detail.retainedCapabilities.join('、') || '正常权益按套餐版本执行'
        }}</el-descriptions-item>
        <el-descriptions-item label="摘要" :span="4"
          ><code>{{ detail.subscription.contentSha256 }}</code></el-descriptions-item
        >
      </el-descriptions>
      <el-form :inline="true" class="mt-3">
        <el-form-item label="原因"><el-input v-model="reason" class="wide" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['subscription:activate']" @click="execute('activate')">激活</el-button>
          <el-button v-hasPermi="['subscription:renew']" @click="execute('renew')">续期</el-button>
          <el-button v-hasPermi="['subscription:suspend']" type="warning" @click="execute('suspend')">暂停</el-button>
          <el-button v-hasPermi="['subscription:restore']" type="success" @click="execute('restore')">恢复</el-button>
          <el-button v-hasPermi="['subscription:terminate']" type="danger" @click="execute('request-termination')">申请终止</el-button>
          <el-button v-hasPermi="['subscription:terminate']" type="danger" @click="execute('terminate')">确认逻辑终止</el-button>
        </el-form-item>
      </el-form>
      <el-table :data="detail.terms" class="mt-3" border>
        <el-table-column prop="termVersion" label="期限版本" width="100" /><el-table-column prop="startsAt" label="开始 UTC" />
        <el-table-column prop="endsAt" label="结束 UTC" /><el-table-column prop="graceEndsAt" label="宽限结束 UTC" />
        <el-table-column prop="contractRef" label="合同引用" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {
  activateSubscription,
  createSubscription,
  getSubscription,
  renewSubscription,
  requestSubscriptionTermination,
  restoreSubscription,
  suspendSubscription,
  terminateSubscription
} from '@/api/subscription';
import type { SubscriptionIdentity } from '@/api/subscription';
import type { SubscriptionDetail } from '@/api/subscription/types';
import { newOperationCommandId } from '@/api/operations';

const loading = ref(false),
  targetTenant = ref('TENANT_A'),
  subscriptionId = ref(''),
  detail = ref<SubscriptionDetail>(),
  reason = ref('已完成受控状态与历史保留复核');
const start = new Date(Date.now() - 60_000),
  end = new Date(Date.now() + 365 * 86400000),
  grace = new Date(end.getTime() + 30 * 86400000);
const iso = (d: Date) => d.toISOString().slice(0, 19);
const term = reactive({
  contractRef: 'CONTRACT-SYNTHETIC-001',
  externalOrderRef: 'ORDER-SYNTHETIC-001',
  startsAt: iso(start),
  endsAt: iso(end),
  graceEndsAt: iso(grace),
  businessTimeZone: 'Asia/Shanghai'
});
const keys = new Map<string, string>();
const identity = (action: string): SubscriptionIdentity => {
  if (!keys.has(action)) keys.set(action, newOperationCommandId());
  const value = keys.get(action)!;
  return { idempotencyKey: value, correlationId: value };
};
const run = async <T,>(work: () => Promise<T>) => {
  loading.value = true;
  try {
    return await work();
  } finally {
    loading.value = false;
  }
};
const command = async <T,>(action: string, work: (i: SubscriptionIdentity) => Promise<T>) => {
  const result = await run(() => work(identity(action)));
  keys.delete(action);
  return result;
};
const create = async () => {
  detail.value = (
    await command('create', (i) => createSubscription(targetTenant.value, { ...term, degradationPolicyVersion: 'RECOVERY-V1' }, i))
  ).data;
  subscriptionId.value = detail.value.subscription.subscriptionId;
};
const refresh = async () => {
  detail.value = (await run(() => getSubscription(subscriptionId.value))).data;
};
const execute = async (action: 'activate' | 'renew' | 'suspend' | 'restore' | 'request-termination' | 'terminate') => {
  if (!detail.value) return;
  const id = subscriptionId.value;
  detail.value = (
    await command(action, (i) =>
      action === 'activate'
        ? activateSubscription(id, i)
        : action === 'renew'
          ? renewSubscription(id, term, i)
          : action === 'suspend'
            ? suspendSubscription(id, reason.value, i)
            : action === 'restore'
              ? restoreSubscription(id, term, i)
              : action === 'request-termination'
                ? requestSubscriptionTermination(id, reason.value, i)
                : terminateSubscription(id, reason.value, i)
    )
  ).data;
};
</script>

<style scoped>
.subscription-operations .wide {
  width: 360px;
}
.mt-3 {
  margin-top: 12px;
}
</style>
