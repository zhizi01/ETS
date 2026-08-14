<script setup>
import { computed } from 'vue'
import * as E from '@/utils/enums'

const DICTS = {
  sim: [E.SIM_STATE, E.SIM_STATE_COLOR],
  session: [E.TSESSION_STATE, E.TSESSION_STATE_COLOR],
  order: [E.ORDER_STATUS, E.ORDER_STATUS_COLOR],
  listing: [E.LISTING_STATUS, E.LISTING_STATUS_COLOR],
  nego: [E.NEGO_STATUS, E.NEGO_STATUS_COLOR],
  contract: [E.CONTRACT_STATUS, E.CONTRACT_STATUS_COLOR],
  member: [E.MEMBER_TYPE, E.MEMBER_TYPE_COLOR],
  notice: [E.NOTICE_LEVEL, E.NOTICE_LEVEL_COLOR],
  spot: [E.SPOT_BID_STATUS, {}],
  mode: [E.TRADE_MODE, {}],
}

const props = defineProps({
  kind: { type: String, required: true },
  value: { type: String, default: '' },
  /** 后端 VO 大多已带 xxxLabel，优先用它，减少前后端字典漂移 */
  label: { type: String, default: '' },
  color: { type: String, default: '' },
})

const pair = computed(() => DICTS[props.kind] || [{}, {}])
const text = computed(() => props.label || pair.value[0][props.value] || props.value || '—')
const tone = computed(() => props.color || pair.value[1][props.value] || 'default')
</script>

<template>
  <a-tag :color="tone" :bordered="false">{{ text }}</a-tag>
</template>
