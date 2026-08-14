<script setup>
import { Inbox } from 'lucide-vue-next'

defineProps({
  text: { type: String, default: '暂无数据' },
  hint: { type: String, default: '' },
  compact: { type: Boolean, default: false },
})
</script>

<template>
  <div class="empty-hint" :class="{ compact }">
    <slot name="icon"><Inbox :size="compact ? 18 : 26" :stroke-width="1.4" /></slot>
    <div class="txt">{{ text }}</div>
    <div v-if="hint" class="sub">{{ hint }}</div>
    <div v-if="$slots.action" class="act"><slot name="action" /></div>
  </div>
</template>

<style scoped>
.empty-hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 36px 16px;
  color: var(--ets-text-faint);
  text-align: center;
  height: 100%;
  min-height: 120px;
}
.empty-hint.compact {
  padding: 16px 10px;
  min-height: 64px;
  gap: 4px;
}
.txt {
  font-size: 12px;
}
.sub {
  font-size: 11px;
  color: #4d5768;
  max-width: 300px;
  line-height: 1.6;
}
.act {
  margin-top: 4px;
}
</style>
