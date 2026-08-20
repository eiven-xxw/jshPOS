<template>
  <div class="operation-panel">
    <el-alert
      type="error"
      :closable="false"
      show-icon
      title="仅合成发布治理"
      description="本页只登记合成签名包和虚构终端范围；不安装 APK、不调用厂商 SDK、不发送固件、重启或真实远程命令。"
    />
    <el-card class="mt-3" shadow="never">
      <template #header><span>发布物状态与兼容窗口</span></template>
      <el-form :inline="true">
        <el-form-item label="Release ULID"><el-input v-model="form.releaseId" class="id-input" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.artifactType" class="medium-input">
            <el-option v-for="type in artifactTypes" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本"><el-input v-model="form.version" class="medium-input" /></el-form-item>
        <el-form-item label="目标门店"><el-input v-model="form.storeIds" class="medium-input" placeholder="1101,1102" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['release:read']" @click="loadRelease">查询</el-button>
          <el-button v-hasPermi="['release:create']" type="primary" @click="createSyntheticRelease">创建合成发布</el-button>
          <el-button v-hasPermi="['release:verify']" @click="releaseAction('verify')">验签冻结</el-button>
          <el-button v-hasPermi="['release:rollout']" type="warning" @click="releaseAction('stage')">进入灰度</el-button>
          <el-button v-hasPermi="['release:revoke']" type="danger" @click="releaseAction('revoke')">吊销</el-button>
        </el-form-item>
      </el-form>
      <el-descriptions v-if="release" :column="4" border>
        <el-descriptions-item label="服务端状态">{{ release.state }}</el-descriptions-item>
        <el-descriptions-item label="版本">{{ release.version }}</el-descriptions-item>
        <el-descriptions-item label="构建提交">{{ release.buildCommit }}</el-descriptions-item>
        <el-descriptions-item label="门店数量">{{ release.targetStoreCount }}</el-descriptions-item>
        <el-descriptions-item label="清单摘要" :span="2">{{ release.manifestSha256 }}</el-descriptions-item>
        <el-descriptions-item label="SBOM 摘要" :span="2">{{ release.sbomSha256 }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card class="mt-3" shadow="never">
      <template #header><span>灰度编排（虚构终端软件证据）</span></template>
      <el-form :inline="true">
        <el-form-item label="Rollout ULID"><el-input v-model="form.rolloutId" class="id-input" /></el-form-item>
        <el-form-item label="金丝雀比例"><el-input-number v-model="form.canaryPercent" :min="1" :max="25" /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['release:rollout']" type="primary" @click="createSyntheticRollout">创建灰度</el-button>
          <el-button v-hasPermi="['release:rollout']" @click="rolloutAction('start-canary')">启动金丝雀</el-button>
          <el-button v-hasPermi="['release:rollout']" @click="rolloutAction('expand')">扩量</el-button>
          <el-button v-hasPermi="['release:rollout']" type="warning" @click="rolloutAction('pause')">暂停</el-button>
          <el-button v-hasPermi="['release:rollout']" type="success" @click="rolloutAction('complete')">完成</el-button>
        </el-form-item>
      </el-form>
      <el-descriptions v-if="rollout" :column="4" border>
        <el-descriptions-item label="状态">{{ rollout.state }}</el-descriptions-item>
        <el-descriptions-item label="发布 ID">{{ rollout.releaseId }}</el-descriptions-item>
        <el-descriptions-item label="金丝雀比例">{{ rollout.canaryPercent }}%</el-descriptions-item>
        <el-descriptions-item label="门店数量">{{ rollout.targetStoreCount }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { createRelease, createRollout, getRelease, newOperationCommandId, transitionRelease, transitionRollout } from '@/api/operations';
import type { ReleaseCreateRequest, ReleaseSummary, RolloutSummary } from '@/api/operations/types';
import { parseSafePlatformIds } from '../model';
import { useControlledOperation } from '../useControlledOperation';

const { runRead, runControlled } = useControlledOperation();
const artifactTypes: ReleaseCreateRequest['artifactType'][] = [
  'SERVER',
  'WEB',
  'MYSQL_SCHEMA',
  'SQLITE_SCHEMA',
  'TEMPLATE_PACKAGE',
  'DATA_PACKAGE',
  'APK'
];
const syntheticSha = 'a'.repeat(64);
const form = reactive({
  releaseId: '',
  rolloutId: '',
  artifactType: 'WEB' as ReleaseCreateRequest['artifactType'],
  version: '0.0.0-synthetic.1',
  storeIds: '1101,1102',
  canaryPercent: 10
});
const release = ref<ReleaseSummary>();
const rollout = ref<RolloutSummary>();

const loadRelease = async () => {
  release.value = await runRead(() => getRelease(form.releaseId));
};
const createSyntheticRelease = async () => {
  const stores = parseSafePlatformIds(form.storeIds, 10_000);
  const changed = await runControlled({
    owner: 'Release',
    objectId: `${form.artifactType}:${form.version}`,
    currentState: 'LOCAL_DRAFT',
    currentVersion: 0,
    action: 'CREATE_SYNTHETIC_RELEASE',
    impact: '登记合成发布清单和兼容窗口，不向真实终端发送命令',
    reason: '内部候选发布治理演练',
    execute: (key) =>
      createRelease(
        {
          artifactType: form.artifactType,
          version: form.version,
          channel: 'INTERNAL',
          objectKey: `synthetic/gate6e/${form.artifactType.toLowerCase()}/${form.version}`,
          artifactSha256: syntheticSha,
          signatureBase64: 'U1lOVEhFVElDX1NJR05BVFVSRV9PTkxZX0dBVEU2RQ==',
          keyVersion: 'synthetic-gate6e-v1',
          buildCommit: '1'.repeat(40),
          sbomSha256: 'b'.repeat(64),
          compatibility: {
            minAppVersion: '0.0.0',
            maxAppVersion: '99.0.0',
            minProtocolVersion: '1',
            maxProtocolVersion: '1',
            minSchemaVersion: '1',
            maxSchemaVersion: '999',
            minSystemVersion: '1',
            maxSystemVersion: '99',
            requiredCapabilitySha256: ''
          },
          targetStoreIds: stores
        },
        key
      )
  });
  if (changed) {
    release.value = changed;
    form.releaseId = changed.releaseId;
  }
};

const releaseAction = async (action: 'verify' | 'stage' | 'revoke') => {
  if (!release.value) return ElMessage.warning('请先读取服务端发布状态');
  const changed = await runControlled({
    owner: 'Release',
    objectId: release.value.releaseId,
    currentState: release.value.state,
    currentVersion: 0,
    action: action.toUpperCase(),
    impact: '推进 Provider 无关发布状态，不发送真实终端命令',
    reason: '内部合成发布职责链确认',
    execute: (key) => transitionRelease(release.value!.releaseId, action, key)
  });
  if (changed) release.value = changed;
};

const createSyntheticRollout = async () => {
  if (!release.value) return ElMessage.warning('请先读取或创建发布物');
  const changed = await runControlled({
    owner: 'Release.Rollout',
    objectId: release.value.releaseId,
    currentState: release.value.state,
    currentVersion: 0,
    action: 'CREATE_ROLLOUT',
    impact: '创建虚构终端灰度编排，不下发 APK 或设备命令',
    reason: '内部灰度状态机演练',
    execute: (key) =>
      createRollout(release.value!.releaseId, { targetStoreIds: parseSafePlatformIds(form.storeIds, 10_000), canaryPercent: form.canaryPercent }, key)
  });
  if (changed) {
    rollout.value = changed;
    form.rolloutId = changed.rolloutId;
  }
};

const rolloutAction = async (action: 'start-canary' | 'expand' | 'pause' | 'complete') => {
  if (!rollout.value) return ElMessage.warning('请先创建灰度并取得服务端状态');
  const changed = await runControlled({
    owner: 'Release.Rollout',
    objectId: rollout.value.rolloutId,
    currentState: rollout.value.state,
    currentVersion: 0,
    action: action.toUpperCase(),
    impact: '推进合成灰度状态；真实终端和厂商命令执行数保持 0',
    reason: '内部候选灰度职责链确认',
    execute: (key) => transitionRollout(rollout.value!.rolloutId, action, key)
  });
  if (changed) rollout.value = changed;
};
</script>

<style scoped>
.id-input {
  width: 280px;
}
.medium-input {
  width: 220px;
}
</style>
