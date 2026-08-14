# 地基速查（已写好，直接用，不要重复造）

包根：`com.github.zhizi01.ets`

## common
| 类 | 用法 |
|---|---|
| `R<T>` | `R.ok(data)` / `R.ok()` / `R.fail(ErrorCode.X)` / `R.fail(ErrorCode.X, "自定义")`。Controller 一律返回 `R<...>` |
| `PageResult<T>` | `PageResult.slice(allList, page, size)` 做内存分页；`PageResult.of(list,total,page,size)` |
| `ErrorCode` | 已覆盖全部业务错误码，缺什么在枚举里加（这是唯一允许改地基的地方） |
| `BizException` | `throw BizException.of(ErrorCode.X)`；`BizException.check(cond, ErrorCode.X)` |
| `GlobalExceptionHandler` | 已全局兜底，Controller 里不要 try/catch |
| `JsonUtils` | `toJson / fromJson / toList / toMap / fromMap / copy`。**Jackson 3，不要自己 new ObjectMapper** |
| `Nums` | `price(v)` 2位、`qty(v)` 3位、`amount(v)` 2位、`alignTick(v,tick)`、`clamp`、`gt/gte/eq`、`div` |
| `IdGen` | `@Autowired IdGen idGen;` → `idGen.next(IdGen.ORDER)` 得 `O000123`；`idGen.nextSeq()` 得全局撮合序号 |

## repo（全部已实现，注入即可用）
`RedisKeys`（所有 key 的唯一来源）、`RedisStore`（Redis 门面）、`BaseRepo<T>`（实体基类）

具体仓储：`UserRepo` `AdminRepo` `SimRepo` `AccountRepo` `PositionRepo` `FundFlowRepo`
`TradingSessionRepo` `OrderRepo` `TradeRepo` `ListingRepo` `TakeOrderRepo` `NegotiationRepo`
`SpotBidRepo` `ContractRepo` `BillRepo` `RobotRepo` `MarketEventRepo` `NoticeRepo`
`QuoteRepo` `KlineRepo` `AuditLogRepo`

常用方法模式：
```java
account = accountRepo.find(id);                       // 取
account = accountRepo.require(id, ErrorCode.ACCOUNT_NOT_FOUND);
accountRepo.saveAccount(account);                     // 存 + 维护索引
accountRepo.listBySim(simId);
orderRepo.saveOrder(o); orderRepo.update(o);          // update 只写实体不动索引
tradeRepo.recentBySession(sessionId, 50);
klineRepo.upsert(simId, ik, "1m", bar);
klineRepo.recent(simId, ik, "1m", 200);               // 时间升序
simRepo.updateRank(simId, acctId, totalPnl);
```

`RedisStore` 值得注意的能力：
```java
store.withLock("listing:" + id, Duration.ofSeconds(3), 800, () -> { ... });  // 分布式锁，抢不到返回 null
store.deleteByPattern(RedisKeys.P + "sim:S0001:*");                          // 重置演练
store.publish(RedisKeys.pubWs(), json);
store.multiGet(keys, Type.class);
```

## security
| 类 | 用法 |
|---|---|
| `LoginUser` | `id / username / nickname / role / loginAt`，`isAdmin()` |
| `@CurrentUser` | Controller 方法参数上加，直接拿到 `LoginUser` |
| `AuthContext` | `AuthContext.require()` / `AuthContext.userId()`，非 Controller 场景用 |
| `TokenService` | `issue(id, username, nickname, role)` / `resolve(token)` / `revoke` / `revokeAll` |
| `PasswordUtil` | `hash(raw)` / `matches(raw, stored)` |
| `AuthInterceptor` | 已按路径前缀鉴权：`/api/user/**`=USER，`/api/admin/**`=ADMIN，`/api/pub/**` 放行 |

## config
`EtsProperties`（`ets.*` 全部配置）、`WebMvcConfig`（CORS+拦截器+参数解析器）、
`AsyncConfig` 提供四个线程池 Bean：
```java
@Qualifier("matchExecutor")   ExecutorService           // 撮合
@Qualifier("robotScheduler")  ScheduledExecutorService  // 机器人 tick
@Qualifier("marketScheduler") ScheduledExecutorService  // 行情推送/K线
ThreadPoolTaskScheduler                                 // 定时任务
```

## api（跨模块端口，只依赖接口，注入时加 @Lazy）
| 端口 | 实现方 | 关键方法 |
|---|---|---|
| `AccountPort` | 账户模块 | `require` `requireOwned` `requireTradable` `listBySim` `createRobotAccount` |
| `FundPort` | 账户模块 | `freezeForBuy` `freezeMargin` `unfreezeBuy` `releaseMargin` `applyTrade` `markToMarket` `deposit` `adjust` |
| `SimPort` | 模拟盘模块 | `requireSim` `requireRunning` `requireSession` `listOpenSessions` `saveSession` |
| `TradePort` | 撮合模块 | `placeOrder(PlaceOrderCmd)` `cancelOrder` `cancelAll` `createListing(CreateListingCmd)` `takeListing` `runCallAuction` `runSpotClearing` `closeSession` |
| `ClearingPort` | 撮合模块 | `createContract` `settle` |
| `MarketPort` | 行情模块 | `onTrade` `onBookChanged` `quote` `referencePrice` `midPrice` `depth` `initInstrument` |
| `WsPort` | 推送模块 | `push(topic, data)` `push(topic, type, data)` `onlineCount` |
| `RobotPort` | 机器人模块 | `spawnDefaultRobots` `startSim` `stopSim` `setEnabled` `runningCount` |
| `AuditPort` | 管理模块 | `log(...)` `system(...)` |

命令对象：`PlaceOrderCmd`（Builder）、`CreateListingCmd`（Builder）、`Depth`（含 `Depth.Level`）

## domain
`domain/enums/*`：见 DESIGN.md 第 4 节，全部实现 `LabeledEnum`（有 `getLabel()` 中文名）。
`domain/model/*`：`User Admin Sim SimConfig TradingAccount AccountParams FundInfo AccountStat
TradingSession Order Trade Listing TakeOrder Negotiation NegoRound SpotBid SpotSegment
Contract Bill Position FundFlow Robot RobotConfig MarketEvent Notice Fundamental Quote Bar AuditLog`

便利方法：`TradingSession.instrumentKey()`、`TradingSession.instrumentKey(variety, periodType)`（静态）、
`Order.remainQty()`、`Listing.expired(now)`、`Negotiation.sideOf(acctId)/buyAccountId()/sellAccountId()`、
`Bar.init(ts, price)` 与 `bar.apply(price, qty)`、`LoadProfile.hourlyShape()`、`GenType.getTypicalCost()`、
`PeriodType.getPriceFactor()`、`MarketEvent.priceImpact()`。
