<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { sessionApi } from '../../api'
import { PERIOD_TYPE, TRADE_MODE, VARIETY } from '../../utils/enums'
import { epochToStr, strToEpoch } from '../../utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  record: { type: Object, default: null },
  simId: { type: String, default: '' },
})
const emit = defineEmits(['update:open', 'saved'])

const isEdit = computed(() => !!props.record?.id)
const submitting = ref(false)

/** 集中出清类场次才有集中申报窗口 */
const centralized = computed(() => ['CALL_AUCTION', 'SPOT_CLEARING'].includes(form.tradeMode))

const form = reactive({
  name: '',
  variety: 'MONTHLY',
  periodType: 'ALL',
  tradeMode: 'ROLLING_MATCH',
  deliveryStart: null,
  deliveryEnd: null,
  scheduleMode: 'relative',
  openAfterMinutes: 1,
  durationMinutes: 180,
  preOpenTime: null,
  openTime: null,
  closeTime: null,
  priceFloor: null,
  priceCap: null,
  minQty: null,
  maxQty: null,
  tickSize: null,
  lotSize: null,
})

function reset() {
  const r = props.record
  form.name = r?.name || ''
  form.variety = r?.variety || 'MONTHLY'
  form.periodType = r?.periodType || 'ALL'
  form.tradeMode = r?.tradeMode || 'ROLLING_MATCH'
  form.deliveryStart = r?.deliveryStart || null
  form.deliveryEnd = r?.deliveryEnd || null
  form.scheduleMode = r ? 'absolute' : 'relative'
  form.openAfterMinutes = 1
  form.durationMinutes = 180
  form.preOpenTime = r?.preOpenTime ? epochToStr(r.preOpenTime) : null
  form.openTime = r?.openTime ? epochToStr(r.openTime) : null
  form.closeTime = r?.closeTime ? epochToStr(r.closeTime) : null
  form.priceFloor = r?.priceFloor ?? null
  form.priceCap = r?.priceCap ?? null
  form.minQty = r?.minQty ?? null
  form.maxQty = r?.maxQty ?? null
  form.tickSize = r?.tickSize ?? null
  form.lotSize = r?.lotSize ?? null
}

watch(
  () => props.open,
  (v) => v && reset(),
)

async function submit() {
  if (!isEdit.value && !props.simId) {
    message.warning('请先在顶栏选择演练场')
    return
  }
  if (form.priceFloor != null && form.priceCap != null && form.priceFloor > form.priceCap) {
    message.warning('申报价格下限不能高于上限')
    return
  }
  submitting.value = true
  try {
    const common = {
      name: form.name?.trim() || null,
      deliveryStart: form.deliveryStart || null,
      deliveryEnd: form.deliveryEnd || null,
      priceFloor: form.priceFloor,
      priceCap: form.priceCap,
      minQty: form.minQty,
      maxQty: form.maxQty,
      tickSize: form.tickSize,
      lotSize: form.lotSize,
    }

    if (isEdit.value) {
      await sessionApi.update(props.record.id, {
        ...common,
        preOpenTime: strToEpoch(form.preOpenTime),
        openTime: strToEpoch(form.openTime),
        closeTime: strToEpoch(form.closeTime),
      })
      message.success('场次已更新')
    } else {
      const body = {
        ...common,
        simId: props.simId,
        variety: form.variety,
        periodType: form.periodType,
        tradeMode: form.tradeMode,
      }
      if (form.scheduleMode === 'relative') {
        body.openAfterMinutes = form.openAfterMinutes
        body.durationMinutes = form.durationMinutes
      } else {
        body.preOpenTime = strToEpoch(form.preOpenTime)
        body.openTime = strToEpoch(form.openTime)
        body.closeTime = strToEpoch(form.closeTime)
      }
      await sessionApi.create(body)
      message.success('场次已创建')
    }
    emit('saved')
    emit('update:open', false)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="open"
    :title="isEdit ? `编辑场次 · ${record?.name}` : '新建交易场次'"
    :width="760"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    centered
    @update:open="(v) => emit('update:open', v)"
    @ok="submit"
  >
    <a-alert
      v-if="isEdit"
      type="info"
      show-icon
      banner
      style="margin-bottom: 14px"
      message="品种、时段与交易方式决定了标的键与撮合方式，订单簿建立后不可修改，故此处不开放。"
    />

    <a-form layout="vertical">
      <div class="g3">
        <a-form-item label="交易品种" required>
          <a-select v-model:value="form.variety" :disabled="isEdit" :options="VARIETY" />
        </a-form-item>
        <a-form-item label="交易时段">
          <a-select v-model:value="form.periodType" :disabled="isEdit" :options="PERIOD_TYPE" />
        </a-form-item>
        <a-form-item label="交易方式" required>
          <a-select v-model:value="form.tradeMode" :disabled="isEdit" :options="TRADE_MODE" />
        </a-form-item>
      </div>

      <a-form-item label="场次名称">
        <a-input v-model:value="form.name" :maxlength="60" placeholder="留空则按「品种+时段·交易方式」自动生成" />
      </a-form-item>

      <div class="g2">
        <a-form-item label="交割起始日">
          <a-date-picker
            v-model:value="form.deliveryStart"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="留空按品种与基准日推算"
          />
        </a-form-item>
        <a-form-item label="交割截止日">
          <a-date-picker
            v-model:value="form.deliveryEnd"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="留空按品种与基准日推算"
          />
        </a-form-item>
      </div>

      <a-divider orientation="left" style="margin: 6px 0 14px">
        <span class="dt">时间轴</span>
      </a-divider>

      <a-radio-group v-if="!isEdit" v-model:value="form.scheduleMode" size="small" style="margin-bottom: 12px">
        <a-radio-button value="relative">相对排期（推荐）</a-radio-button>
        <a-radio-button value="absolute">指定绝对时刻</a-radio-button>
      </a-radio-group>

      <div v-if="!isEdit && form.scheduleMode === 'relative'" class="g2">
        <a-form-item label="开市延迟（分钟）">
          <a-input-number v-model:value="form.openAfterMinutes" :min="0" :max="1440" style="width: 100%" />
        </a-form-item>
        <a-form-item :label="centralized ? '申报窗口时长（分钟）' : '持续时长（分钟）'">
          <a-input-number v-model:value="form.durationMinutes" :min="1" :max="10080" style="width: 100%" />
        </a-form-item>
        <div class="tip-span">
          相对当前时刻排期；演练开盘时后端会整体平移时间轴，保证节奏一致。
          <template v-if="centralized">集中出清类场次的窗口结束即出清并闭市。</template>
        </div>
      </div>

      <div v-else class="g3">
        <a-form-item v-if="centralized" label="集中申报开始">
          <a-date-picker
            v-model:value="form.preOpenTime"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="开市时间">
          <a-date-picker
            v-model:value="form.openTime"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="闭市时间">
          <a-date-picker
            v-model:value="form.closeTime"
            show-time
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 100%"
          />
        </a-form-item>
      </div>

      <a-divider orientation="left" style="margin: 6px 0 14px">
        <span class="dt">申报约束</span>
      </a-divider>

      <div class="g3">
        <a-form-item label="价格下限（元/MWh）">
          <a-input-number v-model:value="form.priceFloor" :min="0" :step="10" style="width: 100%" placeholder="自动" />
        </a-form-item>
        <a-form-item label="价格上限（元/MWh）">
          <a-input-number v-model:value="form.priceCap" :min="0" :step="10" style="width: 100%" placeholder="自动" />
        </a-form-item>
        <a-form-item label="最小申报价差 tickSize">
          <a-input-number v-model:value="form.tickSize" :min="0.01" :step="0.1" style="width: 100%" placeholder="自动" />
        </a-form-item>
        <a-form-item label="最小申报电量（MWh）">
          <a-input-number v-model:value="form.minQty" :min="0" :step="100" style="width: 100%" placeholder="自动" />
        </a-form-item>
        <a-form-item label="最大申报电量（MWh）">
          <a-input-number v-model:value="form.maxQty" :min="0" :step="1000" style="width: 100%" placeholder="自动" />
        </a-form-item>
        <a-form-item label="电量步长 lotSize">
          <a-input-number v-model:value="form.lotSize" :min="1" :step="10" style="width: 100%" placeholder="自动" />
        </a-form-item>
      </div>
    </a-form>
  </a-modal>
</template>

<style scoped>
.g2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.g3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 16px;
}
.tip-span {
  grid-column: 1 / -1;
  margin: -4px 0 12px;
  font-size: 11.5px;
  color: var(--text-muted);
  line-height: 1.7;
}
.dt {
  font-size: 12px;
  color: var(--text-sub);
}
</style>
