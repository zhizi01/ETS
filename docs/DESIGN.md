# 电力交易模拟器 ETS — 总体设计（契约文档）

> 本文件是全项目的**唯一契约来源**。所有模块必须严格遵循此处定义的枚举值、字段名、Redis Key、状态机。
> 修改本文件前请确认不会破坏其它模块。

## 1. 工程结构

```
电力系统交易模拟器/
├── ets-service/     Spring Boot 4.1.0 + Java 17   后端（端口 8080）
├── userDist/        Vue 3.5 + Vite 8 + AntDV 4    用户端（端口 5173）
├── adminDist/       Vue 3.5 + Vite 8 + AntDV 4    管理端（端口 5174）
└── docs/            契约文档
```

## 1.5 技术约束（踩过的坑，务必遵守）

1. **Spring Boot 4.1 使用 Jackson 3.1**，包名是 `tools.jackson.*` 而不是 `com.fasterxml.jackson.*`。
   - `tools.jackson.databind.ObjectMapper` / `SerializationFeature` / `DeserializationFeature`
   - `tools.jackson.databind.json.JsonMapper`（用 `JsonMapper.builder()...build()` 构造，ObjectMapper 已不可变）
   - `tools.jackson.core.type.TypeReference`
   - **注解仍在** `com.fasterxml.jackson.annotation.*`（`@JsonInclude`、`@JsonIgnore`、`@JsonProperty`）
   - `WRITE_DATES_AS_TIMESTAMPS` 已移到 `DateTimeFeature`；Java 8 时间支持内置，无需 `JavaTimeModule`
   - **不要直接 new ObjectMapper()，一律用 `JsonUtils`**
2. **Boot 4 的 starter 改名**：`spring-boot-starter-web` → `spring-boot-starter-webmvc`。
3. **JDK 是 17**，不能用虚拟线程、不能用 Java 21+ 语法（无 `SequencedCollection`、无模式匹配 switch 的 21 特性）。
4. **不引入 JPA / MyBatis / spring-boot-starter-jdbc**。MySQL 归档层用纯 `java.sql` 手写，避免 DataSource 自动配置在数据库不可达时导致启动失败。
5. **实体在 Redis 中以 JSON 字符串存储**（不是 Hash），key 见第 8 节；列表与排序靠 ZSET / SET 索引 id，再批量 `MGET` 回填。持仓与现货出清结果是例外，用 Hash 的 field 存 JSON。
6. 所有时间戳统一用 `long`（epochMillis），日期用 `String` 的 `yyyy-MM-dd`，不用 `LocalDate`/`LocalDateTime` 作为持久化字段。
7. 金额与电量统一用 `double`，收敛精度一律走 `Nums`（价格 2 位、电量 3 位、金额 2 位）。

## 2. 存储策略

| 用途 | 存储 | 说明 |
|---|---|---|
| 运行时全部业务数据 | **Redis 8.0.5** `10.0.0.12:6379` db=**3** 密码 `1234567890aa` | 系统的 System of Record |
| 归档落库 | MySQL（当前不可达） | `ets.mysql.enabled=false` 默认关闭；DDL 见 `db/schema.sql`，联通后置 true 即启用异步归档 |
| 撮合订单簿 | JVM 内存（权威） + Redis 镜像 | 重启时从 Redis 重建 |

**Redis 隔离约定**：db0 已被其它应用（`imsp:*`）占用，ETS 一律使用 **db 3** 且所有 key 以 `ets:` 开头。管理端"重置演练"允许 `FLUSHDB`。

## 3. 后端包结构与归属

```
com.github.zhizi01.ets
├── common/       R, PageResult, BizException, ErrorCode, IdGen, JsonUtils, GlobalExceptionHandler   [地基]
├── config/       EtsProperties, RedisConfig, WebMvcConfig, WebSocketConfig, AsyncConfig, MysqlArchiveConfig [地基]
├── domain/
│   ├── enums/    全部枚举                                                                            [地基]
│   ├── model/    实体 POJO                                                                           [地基]
│   └── dto/      请求/响应 DTO                                                                        [各自补充]
├── repo/         仓储【接口=地基，实现=BE-1】RedisKeys 由地基提供
├── security/     TokenService, AuthInterceptor, CurrentUser, LoginUser                              [地基定义 + BE-1 完善]
├── service/
│   ├── user/     UserService, TradingAccountService, FundService                                    [BE-1]
│   ├── sim/      SimSessionService（模拟盘）, TradingSessionService（场次）, ClockService           [BE-1]
│   ├── trade/    OrderService, ListingService, NegotiationService, SpotBidService, RiskService      [BE-2]
│   ├── clearing/ ContractService, SettlementService, RankService                                    [BE-2]
│   ├── market/   QuoteService, KlineService, DepthService, FundamentalService                       [BE-3]
│   └── admin/    AdminService, MarketEventService, DashboardService, AuditLogService                [BE-3]
├── engine/       OrderBook, MatchResult, RollingMatchEngine, CallAuctionEngine,
│                 ListingEngine, BilateralEngine, SpotClearingEngine, MatchingDispatcher             [BE-2]
├── robot/        RobotEngine, RobotRunner, strategy/*                                               [BE-3]
├── ws/           EtsWebSocketHandler, WsSessionRegistry, WsBroadcaster, WsTopics                    [BE-3]
└── controller/
    ├── pub/      PubAuthController, PubMetaController, PubMarketController                          [BE-1 / BE-3]
    ├── user/     AccountController, OrderController, ListingController, NegotiationController,
    │             SpotController, ContractController, PortfolioController                            [BE-1 / BE-2]
    └── admin/    AdminSimController, AdminSessionController, AdminRobotController,
                  AdminMemberController, AdminEventController, AdminSettleController,
                  AdminSysController                                                                 [BE-3]
```

**文件归属纪律**：每个子代理只允许创建/修改自己名下的包，禁止改动 `common/ config/ domain/enums/ domain/model/ repo/*.java(接口)`。如发现地基缺字段，在自己包内新增 DTO 解决，并在交付说明中列出。

## 4. 领域枚举（全部为契约，值不可改）

```java
MemberType      GENCO(发电企业) | CONSUMER(电力用户) | RETAILER(售电公司)
GenType         COAL(燃煤) | GAS(燃气) | HYDRO(水电) | WIND(风电) | SOLAR(光伏) | NUCLEAR(核电) | STORAGE(储能)
LoadProfile     CONTINUOUS(连续生产) | TWO_SHIFT(两班制) | THREE_SHIFT(三班制) | COMMERCIAL(商业) | RESIDENTIAL(居民)

Variety         ANNUAL(年度) | MONTHLY(月度) | MULTI_DAY(月内多日) | SPOT_DA(日前现货)
PeriodType      ALL(全时段) | PEAK(峰段) | FLAT(平段) | VALLEY(谷段) | SHARP(尖峰)
TradeMode       BILATERAL(双边协商) | CALL_AUCTION(集中竞价) | LISTING(挂牌摘牌)
                | ROLLING_MATCH(滚动撮合) | SPOT_CLEARING(现货集中出清)

Side            BUY(买入/购电) | SELL(卖出/售电)
OrderSource     HUMAN(真人) | ROBOT(机器人)
TimeInForce     GFD(当日有效) | GTC(撤销前有效) | IOC(立即成交剩余撤销) | FOK(全部成交否则撤销)
OrderStatus     ACTIVE(挂单中) | PARTIAL(部分成交) | FILLED(全部成交)
                | CANCELLED(已撤单) | REJECTED(已拒绝) | EXPIRED(已过期)
ListingStatus   LISTED(挂牌中) | PARTIAL_TAKEN(部分摘牌) | FULLY_TAKEN(全部摘牌)
                | WITHDRAWN(已撤牌) | EXPIRED(已过期)
NegoStatus      PROPOSED(已发起) | COUNTERED(已还价) | ACCEPTED(已达成)
                | REJECTED(已拒绝) | WITHDRAWN(已撤回) | EXPIRED(已过期)
ContractStatus  EFFECTIVE(生效) | DELIVERING(交割中) | DELIVERED(已交割) | TERMINATED(已终止)

SimState        SETUP(筹备中) | RUNNING(交易中) | PAUSED(已暂停) | ENDED(已结束)
TSessionState   PENDING(未开始) | PRE_OPEN(集中申报) | OPEN(交易中) | SUSPENDED(临时停牌)
                | CLEARING(出清中) | CLOSED(已闭市) | SETTLED(已结算)

RobotStrategy   MARKET_MAKER(做市商) | TREND(趋势跟随) | COST_BASED(成本定价)
                | NOISE(噪声交易) | ARBITRAGE(跨品种套利) | DEMAND(刚性需求)
MarketEventType FUEL_PRICE_UP(燃料涨价) | FUEL_PRICE_DOWN(燃料降价) | LOAD_SURGE(负荷激增)
                | LOAD_DROP(负荷骤降) | RENEWABLE_HIGH(新能源大发) | RENEWABLE_LOW(新能源小发)
                | UNIT_OUTAGE(机组非停) | POLICY_PRICE_CAP(限价政策) | WEATHER_EXTREME(极端天气)
FlowType        DEPOSIT(初始注资) | FREEZE(冻结) | UNFREEZE(解冻) | TRADE_PAY(购电付款)
                | TRADE_RECV(售电收款) | FEE(手续费) | SETTLE(结算) | DEVIATION(偏差考核) | ADJUST(管理员调整)
Role            USER | ADMIN
```

**instrumentKey（行情/K线的标的键）** = `{Variety}_{PeriodType}`，例：`MONTHLY_ALL`、`ANNUAL_PEAK`、`SPOT_DA_ALL`。

## 5. 核心实体字段

### 5.1 User / Admin
```
User:  id, username, password(BCrypt风格SHA256加盐), nickname, orgName, phone,
       role=USER, frozen(bool), createdAt, lastLoginAt
Admin: id, username, password, nickname, superAdmin(bool), createdAt, lastLoginAt
```
内置管理员：`admin / admin123`（首次启动自动创建）。

### 5.2 SimSession（模拟盘 / 演练场）
```
id, name, description, state(SimState),
ownerAdminId, createdAt, startedAt, endedAt,
clockSpeed(默认1，实时1:1), baseDate(仿真交割起始日 yyyy-MM-dd),
cfg: {
  maxAccountsPerUser(默认3), initialCapitalMin, initialCapitalMax,
  feeRateBuy(默认0.0005), feeRateSell(默认0.0005),
  priceLimitPct(涨跌幅 默认0.15), marginRatio(保证金比例 默认0.20),
  allowNakedShort(默认false), deviationTolerancePct(偏差考核容差 默认0.03),
  benchmarkPrice(基准电价 元/MWh, 默认380)
}
```

### 5.3 TradingAccount（交易账户）
> **`params` 在 SimState 进入 RUNNING 时被冻结（paramsLocked=true），此后一律不可修改。**
```
id, simId, userId, code(账户编号,自动生成 如 G0001/C0001/R0001), name,
memberType(MemberType), source(HUMAN|ROBOT), robotId(机器人账户才有),
paramsLocked(bool), lockedAt,
params: {
  initialCapital,                       // 初始资金（元）
  // GENCO 专有
  genType, installedCapacityMw, annualEnergyMwh, variableCostPerMwh,
  minOutputRatio, maxOutputRatio, forcedOutageRate,
  // CONSUMER 专有
  annualDemandMwh, maxAcceptPricePerMwh, loadProfile, retailBenchmarkPrice,
  // RETAILER 专有
  agentDemandMwh, agentUserCount, retailPricePerMwh,
  // 通用风控
  maxSingleOrderMwh, maxPositionMwh, riskAppetite(1-5)
},
fund:   { available, frozen, margin, totalDeposit },
stat:   { realizedPnl, unrealizedPnl, totalPnl, tradeCount, buyMwh, sellMwh,
          buyAmount, sellAmount, avgBuyPrice, avgSellPrice, feePaid },
createdAt, updatedAt
```

### 5.4 TradingSession（交易场次）
```
id, simId, name, variety(Variety), periodType(PeriodType), tradeMode(TradeMode),
state(TSessionState),
deliveryStart, deliveryEnd,          // 交割区间 yyyy-MM-dd
openTime, closeTime,                 // 场次开闭市时间 epochMillis（实时1:1）
preOpenTime,                         // 集中竞价的申报开始时间（仅 CALL_AUCTION/SPOT_CLEARING）
priceFloor, priceCap,                // 申报价格上下限 元/MWh
minQty, maxQty, tickSize, lotSize,   // 电量与价格步长（MWh / 元）
totalVolume, totalAmount, lastPrice, openPrice, highPrice, lowPrice, avgPrice, prevClose,
clearPrice,                          // 集中竞价统一出清价
createdAt
```

### 5.5 Order（申报单，用于 ROLLING_MATCH / CALL_AUCTION）
```
id, simId, sessionId, accountId, userId, source(OrderSource),
variety, periodType, tradeMode, side, timeInForce,
price, quantity, filledQty, avgFillPrice, frozenAmount,
status(OrderStatus), rejectReason, seq(全局递增，用于时间优先),
createdAt, updatedAt, canceledAt
```

### 5.6 Listing / TakeOrder（挂牌摘牌）
```
Listing:  id, simId, sessionId, accountId, userId, source, side, variety, periodType,
          price, totalQty, remainQty, minTakeQty, allowPartial(bool),
          validUntil(epochMillis), status(ListingStatus), title, remark, createdAt, updatedAt
TakeOrder:id, listingId, simId, accountId, userId, source, quantity, price,
          tradeId, createdAt
```
规则：**不可摘自己的牌**；`allowPartial=false` 时必须整量摘牌；部分摘牌后 `remainQty` 递减，为 0 时 `FULLY_TAKEN`。

### 5.7 Negotiation（双边协商）
```
id, simId, sessionId, variety, periodType,
initiatorAccountId, initiatorUserId, counterpartyAccountId, counterpartyUserId,
initiatorSide(Side),                 // 发起方的买卖方向
price, quantity, deliveryStart, deliveryEnd,
status(NegoStatus), currentTurnAccountId,   // 当前该谁回应
expireAt, rounds[]{ round, byAccountId, price, quantity, message, at },
tradeId, contractId, createdAt, updatedAt
```
规则：轮流还价，最多 `10` 轮；`ACCEPTED` 时按最后一轮价格量成交并生成合同。

### 5.8 SpotBid（日前现货报价曲线）
```
id, simId, sessionId, accountId, userId, source, side, tradeDate,
segments[24]{ hour(0-23), price, quantity },
status(ACTIVE|CLEARED|CANCELLED), createdAt
```
出清：按小时分别做集中出清（供给曲线升序、需求曲线降序求交点），得到 24 个 `clearPrice[h]` 与成交量。

### 5.9 Trade / Contract / Bill
```
Trade:    id, simId, sessionId, variety, periodType, tradeMode,
          buyOrderId, sellOrderId, buyAccountId, sellAccountId,
          price, quantity, amount, buyFee, sellFee, takerSide, tradedAt, seq
Contract: id, simId, tradeId, variety, periodType, buyAccountId, sellAccountId,
          price, quantity, amount, deliveryStart, deliveryEnd,
          status(ContractStatus), signedAt
Bill:     id, simId, accountId, period(如 2026-08), 
          contractQty, contractAmount, actualQty, deviationQty, deviationPenalty,
          spotQty, spotAmount, feeTotal, revenue, cost, profit, rank, settledAt
```

### 5.10 Robot / MarketEvent / Fundamental
```
Robot:  id, simId, accountId, name, strategy(RobotStrategy), enabled,
        cfg: { tickIntervalMs(默认3000), aggressiveness(0-1), priceBiasPct,
               maxOrderMwh, spreadPct, inventoryTargetMwh, randomSeed },
        stat: { orderCount, tradeCount, lastActAt }
MarketEvent: id, simId, type(MarketEventType), magnitude(-1..1), durationMs,
        title, description, effectiveAt, expireAt, createdByAdminId
Fundamental（每个 sim 一份，机器人与行情的基准）:
        coalPriceIdx(1.0基准), gasPriceIdx, loadFactor(1.0基准),
        windFactor, solarFactor, outageRatio, benchmarkPrice, updatedAt
```

## 6. 关键状态机

```
SimState:      SETUP ──start──> RUNNING ──pause──> PAUSED ──resume──> RUNNING ──end──> ENDED
                 ▲                                                                       │
                 └───────────────────────── reset ───────────────────────────────────────┘
  · SETUP  : 用户可自由创建/修改/删除交易账户
  · start  : 校验每个账户参数完整 → 注资 → paramsLocked=true → 生成机器人账户 → 启动引擎

TSessionState: PENDING ─> PRE_OPEN ─> OPEN ─> CLOSED ─> SETTLED
                              │         ├─> SUSPENDED ─> OPEN
                              └─────────┴─> CLEARING ─> CLOSED   (集中竞价/现货)
  · ROLLING_MATCH / LISTING / BILATERAL : PENDING → OPEN → CLOSED → SETTLED
  · CALL_AUCTION / SPOT_CLEARING        : PENDING → PRE_OPEN(只报单不撮合) → CLEARING(统一出清) → CLOSED → SETTLED

Order:  ACTIVE ─> PARTIAL ─> FILLED
           ├─> CANCELLED   ├─> CANCELLED
           ├─> REJECTED    └─> EXPIRED
           └─> EXPIRED
```

## 7. 撮合与出清规则

### 7.1 ROLLING_MATCH（滚动撮合，连续竞价）
- 价格优先、时间优先（同价按 `seq` 升序）。
- 买单价 ≥ 卖单价即成交，**成交价取先到者（Maker）价格**。
- 支持 GFD / GTC / IOC / FOK。
- 撮合权威结构在 JVM 内存 `OrderBook`（`ConcurrentSkipListMap<Long, ArrayDeque<Order>>`，买盘 key 为 `-price*100`，卖盘为 `price*100`），每次变更同步镜像至 Redis。

### 7.2 CALL_AUCTION（集中竞价，统一出清价）
- `PRE_OPEN` 阶段只接受申报、不撮合，盘口对用户隐藏（仅显示申报笔数）。
- `CLEARING` 时：买单价降序、卖单价升序累计，求**最大成交量**对应价格；若存在价格区间，取区间中点四舍五入到 `tickSize`。
- 所有成交均以统一出清价 `clearPrice` 结算；边际单按比例分配（`pro-rata`）。

### 7.3 LISTING（挂牌摘牌）
- 卖方/买方挂牌 → 对手方摘牌，摘牌即成交，成交价 = 挂牌价。
- 校验：不可自摘、`quantity ≥ minTakeQty`、`quantity ≤ remainQty`、`allowPartial=false` 时 `quantity == remainQty`、未过期。
- 高并发下用 Redis 分布式锁 `ets:lock:listing:{listingId}` 串行化。

### 7.4 BILATERAL（双边协商）
- 发起邀约 → 对手方 接受 / 还价 / 拒绝，轮流进行，≤10 轮，超时 `EXPIRED`。
- `ACCEPTED` 立刻生成 Trade + Contract，不进订单簿、不影响公开行情的最新价（但计入成交量统计）。

### 7.5 SPOT_CLEARING（日前现货）
- 每个交易日 24 个小时段独立出清。
- 供给曲线（卖方报价升序累计）与需求曲线（买方报价降序累计）求交点 → 该小时统一出清价与出清电量。
- 无交点时 `clearPrice = 0, volume = 0`。
- 出清价受 `priceFloor / priceCap` 限制。

### 7.6 风控（下单前校验，任一不过即 REJECTED）
1. 模拟盘状态 = RUNNING；场次状态允许接单。
2. 账户属于当前用户且未冻结；`paramsLocked = true`。
3. 价格在 `[priceFloor, priceCap]` 且在 `prevClose × (1 ± priceLimitPct)` 内。
4. 数量 ∈ `[minQty, maxQty]`，且是 `lotSize` 整数倍，且 ≤ `params.maxSingleOrderMwh`。
5. 买方：`available ≥ price × quantity × (1 + feeRateBuy)`，冻结该金额。
6. 卖方：`allowNakedShort=false` 时校验 `可售电量 = annualEnergyMwh - 已售 ≥ quantity`；同时冻结保证金 `price × quantity × marginRatio`。
7. 持仓不超 `params.maxPositionMwh`。

### 7.7 资金与盈亏
- 成交时：买方扣 `amount + buyFee`，卖方收 `amount - sellFee`。
- 发电方 `realizedPnl += (成交价 - variableCostPerMwh) × quantity - fee`。
- 用电方 `realizedPnl += (maxAcceptPricePerMwh - 成交价) × quantity - fee`（相当于避免的高价采购收益）。
- 售电公司 `realizedPnl += (retailPricePerMwh - 成交价) × quantity - fee`。
- `unrealizedPnl` 按最新行情价对未交割持仓做盯市。
- 排行榜 `ets:sim:{id}:rank` 以 `totalPnl` 为 score。

## 8. Redis Key 规范（db=3，全部以 `ets:` 开头）

> 下表中标 `JSON` 的 key 是**普通 String，值为实体的 JSON 序列化结果**（由 `BaseRepo` 统一读写）；
> 只有持仓与现货出清结果用 Hash 的 field 承载多条 JSON。列表与排序一律靠 ZSET / SET 索引 id，
> 取详情时批量 `MGET` 回填。

```
# 序列
ets:seq:{entity}                        STRING  INCR 生成 id

# 账号与令牌
ets:user:{userId}                       JSON
ets:idx:user:username                   HASH    username -> userId
ets:user:all                            ZSET    createdAt -> userId
ets:admin:{adminId}                     JSON
ets:idx:admin:username                  HASH
ets:token:{token}                       STRING  JSON(LoginUser)，TTL 7d
ets:user:{userId}:tokens                SET

# 模拟盘
ets:sim:{simId}                         JSON
ets:sim:all                             ZSET    createdAt -> simId
ets:sim:{simId}:accounts                SET     acctId
ets:sim:{simId}:sessions                ZSET    openTime -> sessionId
ets:sim:{simId}:robots                  SET     robotId
ets:sim:{simId}:events                  ZSET    effectiveAt -> eventId
ets:sim:{simId}:notices                 ZSET    ts -> noticeId
ets:sim:{simId}:rank                    ZSET    totalPnl -> acctId
ets:sim:{simId}:fundamental             JSON
ets:sim:{simId}:instruments             SET     instrumentKey

# 交易账户
ets:acct:{acctId}                       JSON
ets:idx:acct:code:{simId}               HASH    code -> acctId
ets:user:{userId}:accts:{simId}         SET
ets:acct:{acctId}:pos                   HASH    instrumentKey -> JSON(Position)
ets:acct:{acctId}:flow                  ZSET    ts -> flowId
ets:flow:{flowId}                       JSON

# 场次 / 委托 / 成交
ets:tsess:{sessionId}                   JSON
ets:order:{orderId}                     JSON
ets:acct:{acctId}:orders                ZSET    createdAt -> orderId
ets:tsess:{sessionId}:orders            ZSET    seq -> orderId
ets:ob:{sessionId}:bid                  ZSET    -price×100 -> orderId（内存簿的镜像）
ets:ob:{sessionId}:ask                  ZSET     price×100 -> orderId
ets:trade:{tradeId}                     JSON
ets:tsess:{sessionId}:trades            ZSET    tradedAt -> tradeId
ets:acct:{acctId}:trades                ZSET

# 挂牌摘牌
ets:listing:{listingId}                 JSON
ets:tsess:{sessionId}:listings          ZSET    createdAt -> listingId
ets:acct:{acctId}:listings              ZSET
ets:take:{takeId}                       JSON
ets:listing:{listingId}:takes           ZSET

# 双边协商
ets:nego:{negoId}                       JSON    含 rounds 报价轮次数组
ets:acct:{acctId}:negos                 ZSET

# 日前现货
ets:spotbid:{bidId}                     JSON    含 24 点 segments
ets:tsess:{sessionId}:spotbids          ZSET
ets:tsess:{sessionId}:clearing          HASH    hour -> JSON(price, volume)

# 合同 / 结算
ets:contract:{contractId}               JSON
ets:acct:{acctId}:contracts             ZSET
ets:bill:{billId}                       JSON
ets:acct:{acctId}:bills                 ZSET
ets:sim:{simId}:bills:{period}          ZSET    -profit -> billId

# 行情
ets:quote:{simId}:{instrumentKey}       JSON
ets:kline:{simId}:{instrumentKey}:{iv}  ZSET    ts -> JSON(Bar)   iv ∈ 1m,5m,15m,1h,1d

# 机器人 / 事件 / 公告 / 审计 / 锁
ets:robot:{robotId}                     JSON
ets:event:{eventId}                     JSON
ets:notice:{noticeId}                   JSON
ets:log:audit                           ZSET    ts -> logId
ets:log:{logId}                         JSON
ets:lock:{name}                         STRING  SET NX PX
ets:pub:ws                              Pub/Sub 广播频道
```

## 9. 机器人策略要点

| 策略 | 行为 |
|---|---|
| `MARKET_MAKER` | 围绕中间价双边挂单，价差 `spreadPct`，库存偏离目标时单边收窄 |
| `TREND` | 读近 N 根 K 线动量，顺势追价，动量越强越激进 |
| `COST_BASED` | 发电机器人按 `variableCost × (1+margin)` 报卖价；用电机器人按 `maxAcceptPrice × (1-margin)` 报买价 |
| `NOISE` | 在中间价上下随机游走报价，制造流动性与波动 |
| `ARBITRAGE` | 监测 ANNUAL/MONTHLY/SPOT_DA 价差，超阈值时反向下单 |
| `DEMAND` | 刚需买方，临近场次收盘时提高出价确保完成采购量 |

机器人以 `ScheduledExecutorService` 驱动，每个机器人独立 tick（`tickIntervalMs` ± 30% 抖动），全局线程池。除下单外还会**撤单**与**摘牌**，并参与集中竞价申报与现货曲线报价。

## 10. 前端约定

- 请求头 `X-Token`；401 时清 token 跳登录。
- 统一响应 `{ code, msg, data, ts }`，`code !== 0` 时 `message.error(msg)`。
- 开发代理：`/api` 与 `/ws` → `http://localhost:8080`（见各自 `vite.config.js`）。
- 主题：深色专业交易终端风；买入=红（`#f5222d`），卖出=绿（`#00b578`）——遵循国内行情习惯。
- 图表统一用 ECharts 6，按需引入。
