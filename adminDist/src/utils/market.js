/**
 * 基准电价的合成模型，与后端 FundamentalService.multiplier / recalcPrice 完全一致。
 * 复刻一份是为了让管理员在提交事件或调参之前就看到准确的价格预估，而不是等推送回来才知道。
 */

export function clamp(v, lo, hi) {
  return Math.min(Math.max(v, lo), hi)
}

/** 各基本面因子对基准电价的合成弹性 */
export function fundamentalMultiplier(f, eventImpact) {
  if (!f) return 1
  const fuel = (f.coalPriceIdx ?? 1) * 0.65 + (f.gasPriceIdx ?? 1) * 0.35
  const renewable = ((f.windFactor ?? 1) + (f.solarFactor ?? 1)) / 2
  const mFuel = 1 + (fuel - 1) * 0.55
  const mLoad = 1 + ((f.loadFactor ?? 1) - 1) * 0.85
  // 新能源多发压低电价
  const mRenewable = 1 - (renewable - 1) * 0.18
  const mOutage = 1 + ((f.outageRatio ?? 0.03) - 0.03) * 1.5
  return mFuel * mLoad * mRenewable * mOutage * (1 + (eventImpact ?? 0))
}

/** 给定锚点基准价与基本面，推算后端会算出的目标电价（含 ±75%/-55% 的硬边界） */
export function predictBenchmark(base, f, eventImpact) {
  const b = base > 0 ? base : 380
  const target = b * fundamentalMultiplier(f, eventImpact)
  return clamp(target, b * 0.45, b * 1.75)
}

/** 事件冲击累加后受 [-0.6, 0.9] 限幅 */
export function clampEventImpact(v) {
  return clamp(v, -0.6, 0.9)
}
