import { theme } from 'ant-design-vue'

/**
 * 与 styles/theme.css 的 CSS 变量对齐的 antdv token。
 * ConfigProvider 与静态 message / Modal.confirm 共用同一份，
 * 否则二次确认弹窗会以浅色主题渲染，和控制台割裂。
 */
export const antdTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#2f81f7',
    colorSuccess: '#00b578',
    colorError: '#f5222d',
    colorWarning: '#e3b341',
    colorInfo: '#2f81f7',
    colorBgBase: '#0d1117',
    colorBgContainer: '#161b22',
    colorBgElevated: '#1c2129',
    colorBorder: '#30363d',
    colorBorderSecondary: '#21262d',
    colorText: '#e6edf3',
    colorTextSecondary: '#9aa4b0',
    borderRadius: 4,
    fontSize: 13,
  },
  components: {
    Layout: { headerBg: '#161b22', siderBg: '#0f1319', bodyBg: '#0d1117' },
    Menu: { darkItemBg: '#0f1319', darkSubMenuItemBg: '#0b0f14', darkItemSelectedBg: 'rgba(47,129,247,0.16)' },
    Table: { headerBg: '#12171f', rowHoverBg: '#1b222c' },
  },
}
