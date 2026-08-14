import { createApp } from 'vue'
import { createPinia } from 'pinia'
import Antd, { ConfigProvider, message } from 'ant-design-vue'

import App from './App.vue'
import router from './router'
import { antdTheme } from './styles/antd-theme'

import 'ant-design-vue/dist/reset.css'
import './styles/theme.css'

// 静态 message / Modal.confirm 不走组件树，需要单独喂一次主题才会是深色
ConfigProvider.config({ theme: antdTheme })
message.config({ top: '64px', duration: 3, maxCount: 4 })

createApp(App).use(createPinia()).use(router).use(Antd).mount('#app')
