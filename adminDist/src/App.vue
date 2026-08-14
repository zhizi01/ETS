<script setup>
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { antdTheme } from './styles/antd-theme'
</script>

<template>
  <a-config-provider :locale="zhCN" :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>
