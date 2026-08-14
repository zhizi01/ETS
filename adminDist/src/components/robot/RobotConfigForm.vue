<script setup>
/** RobotConfig 全字段编辑，新建与编辑共用 */
const props = defineProps({
  modelValue: { type: Object, required: true },
})
const emit = defineEmits(['update:modelValue'])

function set(key, val) {
  emit('update:modelValue', { ...props.modelValue, [key]: val })
}

const ITEMS = [
  { key: 'tickIntervalMs', label: '决策间隔 tickIntervalMs', unit: 'ms', min: 500, max: 60000, step: 500, precision: 0, hint: '实际执行会加 ±30% 抖动' },
  { key: 'aggressiveness', label: '激进度 aggressiveness', unit: '', min: 0, max: 1, step: 0.05, precision: 2, hint: '越高越愿意吃价' },
  { key: 'priceBiasPct', label: '价格偏移 priceBiasPct', unit: '', min: -0.5, max: 0.5, step: 0.005, precision: 3, hint: '报价相对参考价的系统性偏移' },
  { key: 'maxOrderMwh', label: '单笔上限 maxOrderMwh', unit: 'MWh', min: 500, max: 200000, step: 500, precision: 0, hint: '单笔最大申报电量' },
  { key: 'spreadPct', label: '做市价差 spreadPct', unit: '', min: 0, max: 0.2, step: 0.002, precision: 3, hint: '做市商双边报价的相对价差' },
  { key: 'inventoryTargetMwh', label: '目标库存 inventoryTargetMwh', unit: 'MWh', min: -500000, max: 500000, step: 1000, precision: 0, hint: '偏离目标时单边收窄' },
  { key: 'actProbability', label: '下单概率 actProbability', unit: '', min: 0, max: 1, step: 0.05, precision: 2, hint: '每次 tick 真正下单的概率' },
  { key: 'cancelProbability', label: '撤单概率 cancelProbability', unit: '', min: 0, max: 1, step: 0.05, precision: 2, hint: '每次 tick 撤销既有挂单的概率' },
  { key: 'listingProbability', label: '挂牌概率 listingProbability', unit: '', min: 0, max: 1, step: 0.05, precision: 2, hint: '参与挂牌摘牌的概率' },
]
</script>

<template>
  <div class="cfg-grid">
    <div v-for="it in ITEMS" :key="it.key" class="cfg-item">
      <div class="cfg-label">
        {{ it.label }}
        <span v-if="it.unit" class="cfg-unit">{{ it.unit }}</span>
      </div>
      <div class="cfg-row">
        <a-slider
          :value="Number(modelValue[it.key] ?? 0)"
          :min="it.min"
          :max="it.max"
          :step="it.step"
          class="cfg-slider"
          @update:value="(v) => set(it.key, v)"
        />
        <a-input-number
          :value="Number(modelValue[it.key] ?? 0)"
          :min="it.min"
          :max="it.max"
          :step="it.step"
          :precision="it.precision"
          size="small"
          class="cfg-num"
          @update:value="(v) => set(it.key, v ?? 0)"
        />
      </div>
      <div class="cfg-hint">{{ it.hint }}</div>
    </div>
  </div>
</template>

<style scoped>
.cfg-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px 18px;
}

@media (max-width: 900px) {
  .cfg-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.cfg-label {
  font-size: 11.5px;
  color: var(--text-sub);
  margin-bottom: 2px;
}

.cfg-unit {
  color: var(--text-muted);
}

.cfg-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.cfg-slider {
  flex: 1;
  margin: 4px 0;
}

.cfg-num {
  width: 96px;
  flex: 0 0 96px;
}

.cfg-hint {
  font-size: 10.5px;
  color: var(--text-muted);
  line-height: 1.5;
}
</style>
