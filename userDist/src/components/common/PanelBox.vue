<script setup>
defineProps({
  title: { type: String, default: '' },
  sub: { type: String, default: '' },
  /** flex 布局下让内容区自己滚动 */
  scroll: { type: Boolean, default: false },
  padded: { type: Boolean, default: false },
  flex: { type: Boolean, default: true },
})
</script>

<template>
  <section class="ets-panel panel-box" :class="{ flex }">
    <header v-if="title || $slots.extra" class="ets-panel-title">
      <span class="t">
        {{ title }}
        <span v-if="sub" class="sub">{{ sub }}</span>
      </span>
      <span class="x"><slot name="extra" /></span>
    </header>
    <div class="body" :class="{ 'ets-scroll': scroll, padded }">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.panel-box {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}
.panel-box.flex {
  flex: 1;
}
/* 面板内容默认是纵向弹性容器，子块可以直接用 flex:1 + min-height:0 自己滚动 */
.body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.padded {
  padding: 10px;
}
.t {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}
.x {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
</style>
