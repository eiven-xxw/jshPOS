<template>
  <section :data-testid="`${normalizedSurface}-state`" aria-live="polite">
    <el-skeleton v-if="busy" :rows="2" animated :data-testid="`${normalizedSurface}-loading`" />
    <el-empty v-else-if="state === 'EMPTY'" :description="emptyTitle" :data-testid="`${normalizedSurface}-empty`" />
    <div v-else-if="failure" :data-testid="`${normalizedSurface}-error`">
      <el-alert :type="alertType" :closable="false" show-icon :title="failure.message" :description="failureDescription" />
      <el-button class="mt-2" :type="state === 'UNKNOWN' ? 'warning' : 'primary'" plain :data-testid="`${normalizedSurface}-retry`" @click="emit('retry')">
        {{ state === 'UNKNOWN' ? '查询原操作结果' : '重新加载权威状态' }}
      </el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
import type { OwnerPageFailure } from '../useControlledOperation';

const props = withDefaults(
  defineProps<{
    surfaceId: string;
    state: string;
    failure?: OwnerPageFailure;
    emptyTitle?: string;
  }>(),
  { failure: undefined, emptyTitle: '当前权限与筛选范围内暂无数据' }
);
const emit = defineEmits<{ retry: [] }>();

const normalizedSurface = computed(() => props.surfaceId.toLowerCase());
const busy = computed(() => props.state === 'LOADING' || props.state === 'CONFIRMING' || props.state === 'SUBMITTING');
const alertType = computed(() => (props.state === 'UNKNOWN' || props.state === 'STALE' ? 'warning' : 'error'));
const failureDescription = computed(
  () =>
    `错误码：${props.failure?.code ?? 'OWNER_OPERATION_FAILED'}；关联标识：${props.failure?.correlationId ?? '未返回'}${
      props.failure?.operationIdentity ? `；原操作：${props.failure.operationIdentity}` : ''
    }`
);
</script>
