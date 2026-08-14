<script setup>
defineProps({
  title: { type: String, default: '' },
  bodyStyle: { type: [Object, String], default: null },
  noPad: { type: Boolean, default: false },
})
</script>

<template>
  <section class="panel">
    <header v-if="title || $slots.extra" class="panel-head">
      <div class="panel-title">
        <slot name="title">{{ title }}</slot>
      </div>
      <div class="row-gap">
        <slot name="extra" />
      </div>
    </header>
    <div :class="noPad ? '' : 'panel-body'" :style="bodyStyle">
      <slot />
    </div>
  </section>
</template>
