<script setup>
import { computed, onMounted } from 'vue'
import { useSimStore } from '../stores/sim'
import { SIM_STATE_COLOR } from '../utils/enums'

const props = defineProps({
  width: { type: Number, default: 280 },
  size: { type: String, default: 'small' },
})

const simStore = useSimStore()

const value = computed({
  get: () => simStore.currentId || undefined,
  set: (v) => simStore.setCurrent(v),
})

onMounted(() => simStore.load())
</script>

<template>
  <a-select
    v-model:value="value"
    :size="props.size"
    :style="{ width: props.width + 'px' }"
    :loading="simStore.loading"
    placeholder="选择演练场"
    show-search
    option-filter-prop="label"
  >
    <a-select-option v-for="s in simStore.sims" :key="s.id" :value="s.id" :label="s.name">
      <div class="row-gap" style="justify-content: space-between">
        <span class="nowrap">{{ s.name }}</span>
        <a-tag :color="SIM_STATE_COLOR[s.state]" style="margin: 0">{{ s.stateLabel || s.state }}</a-tag>
      </div>
    </a-select-option>
  </a-select>
</template>
