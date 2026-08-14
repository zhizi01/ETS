# ETS 用户端交易终端（userDist）

电力交易模拟器的**参赛者端**，深色专业交易终端风格。后端为 `ets-service`（Spring Boot 4.1，端口 8080）。

## 运行

```bash
npm install     # 依赖已预装，一般无需执行
npm run dev     # http://localhost:5173
npm run build   # 产物输出到 dist/
```

`vite.config.js` 已配置开发代理：`/api` 与 `/ws`（`ws: true`）转发到 `http://localhost:8080`。

演示账号：`demo1 / demo123`、`demo2 / demo123`；演示模拟盘 `S000001`。

## 目录结构

```
src/
├── api/            axios 封装与按模块拆分的接口定义
│   ├── http.js       X-Token 注入、{code,msg,data} 解包、401 清 token 跳登录
│   ├── pub.js        /api/pub  免鉴权
│   └── user.js       /api/user 需 USER 角色
├── components/
│   ├── common/     NumFlash（等宽数字+跳动高亮）、PanelBox、StateTag、EmptyHint
│   ├── layout/     AppLayout、TopBar、SideNav
│   ├── market/     QuoteList、KlineChart、DepthPanel、TradeTape
│   ├── trade/      OrderTicket、MyOrdersPanel
│   └── EChart.vue  通用图表容器，负责 resize 与 dispose
├── composables/
│   ├── useMarketSocket.js  全局唯一 WS 客户端：订阅计数、20s 心跳、指数退避重连、自动重订阅
│   └── usePolling.js       页面不可见时自动暂停的轮询兜底
├── router/         路由与登录守卫
├── stores/         pinia：auth / sim / account / notice
├── styles/         深色主题变量与 AntDV 覆写
├── utils/          format（金额电量格式化）、enums（枚举中文）、echarts（按需注册）
└── views/          10 个页面
```

## 约定

- 红涨绿跌：买入/上涨 `#f5222d`，卖出/下跌 `#00b578`，平 `#8b98a9`。
- 所有价格、电量、金额一律用等宽字体并右对齐；变动时闪一次背景色。
- 接口路径以 `ets-service` 的 controller 源码为准，与 `docs/API.md` 的差异见交付说明。
