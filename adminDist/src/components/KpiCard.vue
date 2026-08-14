<script setup>
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  unit: { type: String, default: '' },
  hint: { type: String, default: '' },
  /** neutral | up | down | warn | brand */
  tone: { type: String, default: 'neutral' },
  icon: { type: [Object, Function], default: null },
})
</script>

<template>
  <div class="kpi" :class="`kpi-${tone}`">
    <div class="kpi-top">
      <span class="kpi-label">{{ label }}</span>
      <component :is="icon" v-if="icon" :size="15" class="kpi-icon" />
    </div>
    <div class="kpi-value num">
      {{ value }}<span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div class="kpi-hint" :title="hint">{{ hint || '\u00a0' }}</div>
  </div>
</template>

<style scoped>
.kpi {
  position: relative;
  padding: 12px 14px 10px;
  background: var(--bg-panel);
  border: 1px solid var(--border);
  border-radius: 6px;
  overflow: hidden;
}

.kpi::after {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
  background: var(--brand);
  opacity: 0.75;
}

.kpi-up::after {
  background: var(--up);
}
.kpi-down::after {
  background: var(--down);
}
.kpi-warn::after {
  background: var(--warn);
}
.kpi-neutral::after {
  background: #3b444e;
}

.kpi-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.kpi-label {
  font-size: 12px;
  color: var(--text-sub);
  letter-spacing: 0.3px;
}

.kpi-icon {
  color: var(--text-muted);
}

.kpi-value {
  margin-top: 6px;
  font-size: 21px;
  font-weight: 600;
  line-height: 1.15;
  color: var(--text);
}

.kpi-up .kpi-value {
  color: var(--up);
}
.kpi-down .kpi-value {
  color: var(--down);
}
.kpi-warn .kpi-value {
  color: var(--warn);
}

.kpi-unit {
  margin-left: 3px;
  font-size: 11px;
  font-weight: 400;
  color: var(--text-muted);
}

.kpi-hint {
  margin-top: 3px;
  font-size: 11px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
