# 电力交易模拟器 ETS — 接口契约

Base URL：`http://localhost:8080`　认证头：`X-Token: <token>`

统一响应体：
```json
{ "code": 0, "msg": "ok", "data": {}, "ts": 1786649615755 }
```
`code=0` 成功；`401` 未登录/令牌失效；`403` 无权限；其余为业务错误码，`msg` 为中文提示。

分页响应 `data`：`{ "list": [], "total": 0, "page": 1, "size": 20 }`

---

## 一、公共（免鉴权）`/api/pub`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/pub/health` | 健康检查，返回 `{redis, latencyMs, ts}` |
| GET | `/api/pub/meta/enums` | 全部枚举字典 `{MemberType:[{value,label}], ...}` |
| POST | `/api/pub/auth/register` | 用户注册 `{username,password,nickname,orgName,phone}` |
| POST | `/api/pub/auth/login` | 用户登录 `{username,password}` → `{token,user}` |
| POST | `/api/pub/auth/admin/login` | 管理员登录 → `{token,admin}` |
| GET | `/api/pub/sims` | 可见的模拟盘列表 |
| GET | `/api/pub/sims/{simId}` | 模拟盘详情（含 cfg、状态、参与统计） |
| GET | `/api/pub/market/quotes?simId=` | 全标的行情快照数组 |
| GET | `/api/pub/market/quote?simId=&instrumentKey=` | 单标的行情 |
| GET | `/api/pub/market/kline?simId=&instrumentKey=&interval=1m&limit=200` | K 线数组 |
| GET | `/api/pub/market/depth?sessionId=&level=5` | 盘口 `{bids:[[price,qty,count]], asks:[...]}` |
| GET | `/api/pub/market/trades?sessionId=&limit=50` | 逐笔成交 |
| GET | `/api/pub/sessions?simId=` | 交易场次列表 |
| GET | `/api/pub/rank?simId=&limit=50` | 排行榜 |
| GET | `/api/pub/notices?simId=&limit=20` | 历史公告（发布在管理端，这里是公开只读回溯） |
| GET | `/api/pub/events?simId=&limit=20` | 市场事件列表，最新在前 |
| GET | `/api/pub/events/active?simId=` | 当前生效中的市场事件 |

---

## 二、用户端 `/api/user`（角色 USER）

### 2.1 账号
| 方法 | 路径 | 请求体 / 参数 |
|---|---|---|
| GET | `/api/user/auth/me` | 校验令牌并返回当前用户 |
| POST | `/api/user/auth/logout` | 服务端立即吊销当前令牌 |
| POST | `/api/user/auth/password` | `{oldPassword,newPassword}`，改密后全部会话失效 |

### 2.2 交易账户（`SETUP` 阶段可增删改，`RUNNING` 后锁定）
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/user/accounts?simId=` | 我在该模拟盘下的账户列表 |
| POST | `/api/user/accounts` | 创建，见下方请求体 |
| GET | `/api/user/accounts/{id}` | 详情（含 fund/stat/params） |
| PUT | `/api/user/accounts/{id}` | 修改（`paramsLocked=true` 时报错 `账户参数已锁定`） |
| DELETE | `/api/user/accounts/{id}` | 删除（同上限制） |
| GET | `/api/user/accounts/{id}/positions` | 持仓列表 |
| GET | `/api/user/accounts/{id}/flows?page=&size=` | 资金流水 |
| GET | `/api/user/accounts/{id}/summary` | 总览：资金、持仓市值、当日盈亏、排名 |

创建账户请求体：
```json
{
  "simId": "S0001", "name": "华能一厂", "memberType": "GENCO",
  "params": {
    "initialCapital": 50000000,
    "genType": "COAL", "installedCapacityMw": 600, "annualEnergyMwh": 3000000,
    "variableCostPerMwh": 320, "minOutputRatio": 0.4, "maxOutputRatio": 1.0,
    "forcedOutageRate": 0.03,
    "annualDemandMwh": null, "maxAcceptPricePerMwh": null, "loadProfile": null,
    "retailBenchmarkPrice": null, "agentDemandMwh": null, "agentUserCount": null,
    "retailPricePerMwh": null,
    "maxSingleOrderMwh": 50000, "maxPositionMwh": 2000000, "riskAppetite": 3
  }
}
```
> `memberType=GENCO` 必填发电字段；`CONSUMER` 必填用电字段；`RETAILER` 必填售电字段。后端做分支校验。

### 2.3 场次与行情
| 方法 | 路径 |
|---|---|
| GET | `/api/user/sessions?simId=&variety=&tradeMode=&state=` |
| GET | `/api/user/sessions/{id}` |

### 2.4 申报单（滚动撮合 / 集中竞价）
| 方法 | 路径 | 请求体 |
|---|---|---|
| POST | `/api/user/orders` | `{accountId,sessionId,side,price,quantity,timeInForce}` |
| DELETE | `/api/user/orders/{id}` | 撤单 |
| POST | `/api/user/orders/cancel-all` | `{accountId,sessionId}` |
| GET | `/api/user/orders?accountId=&sessionId=&status=&page=&size=` | |
| GET | `/api/user/trades?accountId=&sessionId=&page=&size=` | |

### 2.5 挂牌摘牌
| 方法 | 路径 | 请求体 |
|---|---|---|
| POST | `/api/user/listings` | `{accountId,sessionId,side,price,totalQty,minTakeQty,allowPartial,validUntil,title,remark}` |
| DELETE | `/api/user/listings/{id}` | 撤牌 |
| GET | `/api/user/listings?simId=&sessionId=&side=&status=&mine=false&page=&size=` | `mine=true` 只看自己挂的 |
| GET | `/api/user/listings/{id}` | 详情（含摘牌记录） |
| POST | `/api/user/listings/{id}/take` | `{accountId,quantity}` 摘牌 |

### 2.6 双边协商
| 方法 | 路径 | 请求体 |
|---|---|---|
| GET | `/api/user/counterparties?simId=&accountId=` | 可协商对手方 `[{accountId,code,name,memberType}]` |
| POST | `/api/user/negotiations` | `{accountId,counterpartyAccountId,sessionId,side,price,quantity,deliveryStart,deliveryEnd,message,expireMinutes}` |
| POST | `/api/user/negotiations/{id}/counter` | `{accountId,price,quantity,message}` 还价 |
| POST | `/api/user/negotiations/{id}/accept` | `{accountId}` |
| POST | `/api/user/negotiations/{id}/reject` | `{accountId,message}` |
| POST | `/api/user/negotiations/{id}/withdraw` | `{accountId}` |
| GET | `/api/user/negotiations?accountId=&role=all|initiator|counterparty&status=&page=&size=` | |
| GET | `/api/user/negotiations/{id}` | 详情（含 rounds 报价历史） |

### 2.7 日前现货
| 方法 | 路径 | 请求体 |
|---|---|---|
| POST | `/api/user/spot/bids` | `{accountId,sessionId,side,tradeDate,segments:[{hour,price,quantity}×24]}` |
| DELETE | `/api/user/spot/bids/{id}` | 撤销 |
| GET | `/api/user/spot/bids?accountId=&sessionId=` | |
| GET | `/api/user/spot/clearing?sessionId=` | 24 点出清结果 `[{hour,clearPrice,volume}]` |
| GET | `/api/user/spot/my-result?accountId=&sessionId=` | 我的中标结果 |

### 2.8 合同 / 结算 / 绩效
| 方法 | 路径 |
|---|---|
| GET | `/api/user/contracts?accountId=&status=&page=&size=` |
| GET | `/api/user/bills?accountId=&period=&page=&size=` |
| GET | `/api/user/portfolio?accountId=` 组合分析：品种分布、买卖均价对比、盈亏曲线 |
| GET | `/api/user/notices?simId=&limit=20` 公告历史 |
| GET | `/api/user/notices/feed?simId=&limit=20` 公告 + 市场事件一次返回 |

---

## 三、管理端 `/api/admin`（角色 ADMIN）

### 3.0 认证
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/auth/me` | 校验令牌并返回当前管理员 |
| POST | `/api/admin/auth/logout` | 服务端立即吊销当前令牌 |

### 3.1 模拟盘
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/sims?page=&size=` | |
| POST | `/api/admin/sims` | `{name,description,baseDate,clockSpeed,cfg{...}}` |
| GET/PUT/DELETE | `/api/admin/sims/{id}` | |
| POST | `/api/admin/sims/{id}/start` | 开盘：锁定全部账户参数、注资、启动机器人 |
| POST | `/api/admin/sims/{id}/pause` | 暂停（拒绝新申报，机器人停摆） |
| POST | `/api/admin/sims/{id}/resume` | |
| POST | `/api/admin/sims/{id}/end` | 结束并触发终盘结算 |
| POST | `/api/admin/sims/{id}/reset` | 清空该盘交易数据、账户与机器人，回到 SETUP |
| GET | `/api/admin/sims/{id}/dashboard` | 监控大屏：在线数、成交额、活跃标的、价格走势、异常告警 |

### 3.2 交易场次
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/sessions?simId=&page=&size=` | |
| POST | `/api/admin/sessions` | 创建场次（字段见 DESIGN.md 5.4） |
| POST | `/api/admin/sessions/batch-generate` | `{simId, preset:"STANDARD"}` 一键生成标准场次集合 |
| PUT/DELETE | `/api/admin/sessions/{id}` | |
| POST | `/api/admin/sessions/{id}/open` | 手动开市 |
| POST | `/api/admin/sessions/{id}/pre-open` | 进入集中申报 |
| POST | `/api/admin/sessions/{id}/suspend` | 临时停牌 |
| POST | `/api/admin/sessions/{id}/clear` | 触发集中出清（CALL_AUCTION / SPOT_CLEARING） |
| POST | `/api/admin/sessions/{id}/close` | 闭市 |

### 3.3 机器人
| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/admin/robots?simId=&page=&size=` | |
| GET | `/api/admin/robots/strategies` | 策略元数据与默认参数 |
| POST | `/api/admin/robots` | 单个创建（同时自动建交易账户） |
| POST | `/api/admin/robots/batch` | `{simId,count,strategyMix:{MARKET_MAKER:3,...},memberTypeMix:{...},capitalMin,capitalMax}` |
| POST | `/api/admin/robots/spawn-default` | `?simId=` 补齐默认机器人阵容（已有则不重复） |
| PUT/DELETE | `/api/admin/robots/{id}` | |
| POST | `/api/admin/robots/{id}/enable` \| `/disable` | |
| POST | `/api/admin/robots/enable-all` \| `/disable-all` | `{simId}` |

### 3.4 参与者
| 方法 | 路径 |
|---|---|
| GET | `/api/admin/users?keyword=&page=&size=` |
| POST | `/api/admin/users/{id}/freeze` \| `/unfreeze` |
| POST | `/api/admin/users/{id}/reset-password` |
| GET | `/api/admin/accounts?simId=&memberType=&source=&keyword=&page=&size=` |
| GET | `/api/admin/accounts/{id}/positions` 任意账户的逐标的持仓（用户端同名接口只能查自己的） |
| POST | `/api/admin/accounts/{id}/adjust-fund` `{amount,remark}` |
| POST | `/api/admin/accounts/{id}/force-cancel` 撤销该账户全部挂单 |

### 3.5 市场干预
| 方法 | 路径 |
|---|---|
| GET | `/api/admin/events?simId=&page=&size=` |
| GET | `/api/admin/events/active?simId=` | 当前生效中的事件 |
| POST | `/api/admin/events` `{simId,type,magnitude,durationMinutes,title,description}` |
| DELETE | `/api/admin/events/{id}` |
| GET | `/api/admin/fundamental?simId=` |
| PUT | `/api/admin/fundamental?simId=` `{coalPriceIdx,gasPriceIdx,loadFactor,windFactor,solarFactor,benchmarkPrice}` |
| GET | `/api/admin/notices?simId=&limit=30` |
| POST | `/api/admin/notices` `{simId,title,content,level}` |

### 3.6 结算与系统
| 方法 | 路径 |
|---|---|
| POST | `/api/admin/settle/run` `{simId,period}` |
| GET | `/api/admin/settle/bills?simId=&period=&page=&size=` |
| GET | `/api/admin/rank?simId=&limit=100` |
| GET | `/api/admin/sys/metrics` JVM / Redis / 撮合引擎 指标 |
| GET | `/api/admin/sys/audit?page=&size=` 审计日志 |

---

## 四、WebSocket

连接：`ws://localhost:8080/ws?token=<token>`（管理端同址，鉴权后按角色放行 `admin.*` 主题）

客户端 → 服务端：
```json
{"op":"sub","topics":["quote.S0001.MONTHLY_ALL","depth.T0003"]}
{"op":"unsub","topics":["depth.T0003"]}
{"op":"ping"}
```

服务端 → 客户端：
```json
{"type":"pong","ts":1786649615755}
{"type":"subscribed","topics":[...]}
{"type":"data","topic":"depth.T0003","data":{...},"ts":1786649615755}
{"type":"error","msg":"无权订阅该主题"}
```

主题清单：

| 主题 | 载荷 | 鉴权 |
|---|---|---|
| `quote.{simId}.{instrumentKey}` | 行情快照 | 公开 |
| `depth.{sessionId}` | `{bids,asks}` 五档 | 公开（`PRE_OPEN` 阶段仅返回笔数） |
| `trade.{sessionId}` | 单笔成交 | 公开 |
| `kline.{simId}.{instrumentKey}.{interval}` | 单根 bar 更新 | 公开 |
| `listing.{simId}.{instrumentKey}` | 挂牌新增/变更/撤销 | 公开 |
| `sim.{simId}` | 状态变更、公告、市场事件 | 公开 |
| `order.{accountId}` | 我的委托状态变更 | 账户属主 |
| `account.{accountId}` | 我的资金/持仓/盈亏变更 | 账户属主 |
| `nego.{accountId}` | 双边协商消息 | 账户属主 |
| `admin.dashboard.{simId}` | 管理端监控推送（2s 一次） | ADMIN |

心跳：客户端每 20s 发 `ping`，服务端 60s 无消息则断开；前端断线后指数退避重连并自动重订阅。
