<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { simApi } from '../../api'
import { thousands, todayStr, unThousands } from '../../utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 传入即为编辑模式 */
  record: { type: Object, default: null },
})
const emit = defineEmits(['update:open', 'saved'])

const isEdit = computed(() => !!props.record?.id)
const submitting = ref(false)

const DEFAULT_CFG = {
  maxAccountsPerUser: 3,
  initialCapitalMin: 1000000,
  initialCapitalMax: 500000000,
  feeRateBuy: 0.0005,
  feeRateSell: 0.0005,
  priceLimitPct: 0.15,
  marginRatio: 0.2,
  allowNakedShort: false,
  deviationTolerancePct: 0.03,
  benchmarkPrice: 380,
}

const form = reactive({
  name: '',
  description: '',
  baseDate: todayStr(),
  clockSpeed: 1,
  generateStandardSessions: true,
  cfg: { ...DEFAULT_CFG },
})

function reset() {
  const r = props.record
  form.name = r?.name || ''
  form.description = r?.description || ''
  form.baseDate = r?.baseDate || todayStr()
  form.clockSpeed = r?.clockSpeed || 1
  form.generateStandardSessions = !r
  form.cfg = { ...DEFAULT_CFG, ...(r?.cfg || {}) }
}

watch(
  () => props.open,
  (v) => v && reset(),
)

async function submit() {
  if (!form.name.trim()) {
    message.warning('请填写演练场名称')
    return
  }
  if (form.cfg.initialCapitalMin > form.cfg.initialCapitalMax) {
    message.warning('初始资金下限不能高于上限')
    return
  }
  submitting.value = true
  try {
    const body = {
      name: form.name.trim(),
      description: form.description?.trim() || '',
      baseDate: form.baseDate || null,
      clockSpeed: form.clockSpeed,
      cfg: { ...form.cfg },
    }
    let saved
    if (isEdit.value) {
      saved = await simApi.update(props.record.id, body)
      message.success('演练场已更新')
    } else {
      saved = await simApi.create({ ...body, generateStandardSessions: form.generateStandardSessions })
      message.success(
        form.generateStandardSessions ? '演练场已创建，标准场次集合同步生成' : '演练场已创建',
      )
    }
    emit('saved', saved)
    emit('update:open', false)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <a-modal
    :open="open"
    :title="isEdit ? `编辑演练场 · ${record?.name}` : '新建演练场'"
    :width="760"
    :confirm-loading="submitting"
    ok-text="保存"
    cancel-text="取消"
    centered
    @update:open="(v) => emit('update:open', v)"
    @ok="submit"
  >
    <a-alert
      v-if="isEdit && record?.state !== 'SETUP'"
      type="warning"
      show-icon
      banner
      style="margin-bottom: 14px"
      message="该演练场已开盘，修改规则参数不会回溯已锁定的账户与已成交的交易，请谨慎操作。"
    />

    <a-form layout="vertical">
      <div class="form-grid-2">
        <a-form-item label="演练场名称" required>
          <a-input v-model:value="form.name" placeholder="如：2026年度电力市场演练盘" :maxlength="60" />
        </a-form-item>
        <a-form-item label="仿真交割起始日（baseDate）">
          <a-date-picker
            v-model:value="form.baseDate"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="留空取当天"
          />
        </a-form-item>
      </div>

      <a-form-item label="演练说明">
        <a-textarea
          v-model:value="form.description"
          :rows="2"
          :maxlength="500"
          show-count
          placeholder="面向哪类学员、覆盖哪些品种与交易方式"
        />
      </a-form-item>

      <a-form-item label="时钟倍率（clockSpeed）">
        <a-input-number v-model:value="form.clockSpeed" :min="0.1" :max="60" :step="0.5" style="width: 180px" />
        <span class="hint">当前后端按 1:1 实时推进，倍率为预留字段。</span>
      </a-form-item>

      <a-divider orientation="left" style="margin: 6px 0 14px">
        <span class="divider-title">市场规则 cfg</span>
      </a-divider>

      <div class="form-grid-3">
        <a-form-item label="每人最多账户数">
          <a-input-number v-model:value="form.cfg.maxAccountsPerUser" :min="1" :max="20" style="width: 100%" />
        </a-form-item>
        <a-form-item label="初始资金下限（元）">
          <a-input-number
            v-model:value="form.cfg.initialCapitalMin"
            :min="0"
            :step="1000000"
            :formatter="thousands"
            :parser="unThousands"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="初始资金上限（元）">
          <a-input-number
            v-model:value="form.cfg.initialCapitalMax"
            :min="0"
            :step="1000000"
            :formatter="thousands"
            :parser="unThousands"
            style="width: 100%"
          />
        </a-form-item>

        <a-form-item label="买方手续费率">
          <a-input-number
            v-model:value="form.cfg.feeRateBuy"
            :min="0"
            :max="0.05"
            :step="0.0001"
            :precision="4"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="卖方手续费率">
          <a-input-number
            v-model:value="form.cfg.feeRateSell"
            :min="0"
            :max="0.05"
            :step="0.0001"
            :precision="4"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="涨跌幅限制">
          <a-input-number
            v-model:value="form.cfg.priceLimitPct"
            :min="0.01"
            :max="1"
            :step="0.01"
            :precision="3"
            style="width: 100%"
          />
        </a-form-item>

        <a-form-item label="卖方保证金比例">
          <a-input-number
            v-model:value="form.cfg.marginRatio"
            :min="0"
            :max="1"
            :step="0.05"
            :precision="3"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="偏差考核容差">
          <a-input-number
            v-model:value="form.cfg.deviationTolerancePct"
            :min="0"
            :max="0.5"
            :step="0.01"
            :precision="3"
            style="width: 100%"
          />
        </a-form-item>
        <a-form-item label="基准电价（元/MWh）">
          <a-input-number
            v-model:value="form.cfg.benchmarkPrice"
            :min="1"
            :max="5000"
            :step="10"
            :precision="2"
            style="width: 100%"
          />
        </a-form-item>
      </div>

      <a-form-item>
        <a-switch v-model:checked="form.cfg.allowNakedShort" size="small" />
        <span class="switch-label">允许裸卖空</span>
        <span class="hint">
          关闭时，卖方申报电量受「年度可发电量 − 已售」约束，更贴近真实电力市场的实物交割逻辑。
        </span>
      </a-form-item>

      <a-form-item v-if="!isEdit">
        <a-switch v-model:checked="form.generateStandardSessions" size="small" />
        <span class="switch-label">建盘后立即生成标准场次集合</span>
        <span class="hint">12 个场次：年度 / 月度 / 月内多日 / 日前现货 × 四种交易方式。</span>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<style scoped>
.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.form-grid-3 {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0 16px;
}
.hint {
  margin-left: 10px;
  font-size: 11.5px;
  color: var(--text-muted);
}
.switch-label {
  margin-left: 8px;
  font-size: 12.5px;
}
.divider-title {
  font-size: 12px;
  color: var(--text-sub);
}
</style>
