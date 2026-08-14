<script setup>
import { theme } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'

const etsTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#2f7fff',
    colorBgBase: '#0d1117',
    colorBgContainer: '#161b22',
    colorBgElevated: '#1c2330',
    colorBorder: '#262d38',
    colorBorderSecondary: '#1e242e',
    colorText: '#d7dde7',
    colorTextSecondary: '#8b98a9',
    colorTextTertiary: '#6d7a8b',
    colorSuccess: '#00b578',
    colorError: '#f5222d',
    colorWarning: '#f5a524',
    borderRadius: 6,
    fontSize: 13,
    controlHeight: 30,
    controlHeightSM: 24,
  },
  components: {
    Table: { headerBg: '#1c2330', rowHoverBg: '#222b38', borderColor: '#262d38' },
    Tabs: { horizontalMargin: '0 0 8px 0' },
    Card: { paddingLG: 16 },
  },
}
</script>

<template>
  <a-config-provider :theme="etsTheme" :locale="zhCN">
    <a-app class="ets-root">
      <router-view v-slot="{ Component }">
        <component :is="Component" />
      </router-view>
    </a-app>
  </a-config-provider>
</template>

<style>
.ets-root,
.ets-root > .ant-app {
  height: 100%;
}
</style>
