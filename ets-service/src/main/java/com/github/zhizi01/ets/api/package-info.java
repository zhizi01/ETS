/**
 * 跨模块端口。三个后端模块只通过这里的接口互相调用，实现类各自归属：
 *
 * <ul>
 *   <li>{@link com.github.zhizi01.ets.api.AccountPort}、{@link com.github.zhizi01.ets.api.FundPort}、
 *       {@link com.github.zhizi01.ets.api.SimPort} —— 由账户/模拟盘模块实现</li>
 *   <li>{@link com.github.zhizi01.ets.api.TradePort}、{@link com.github.zhizi01.ets.api.ClearingPort}
 *       —— 由交易撮合模块实现</li>
 *   <li>{@link com.github.zhizi01.ets.api.MarketPort}、{@link com.github.zhizi01.ets.api.WsPort}、
 *       {@link com.github.zhizi01.ets.api.RobotPort} —— 由行情/推送/机器人模块实现</li>
 * </ul>
 *
 * 注入其它模块的端口时一律加 {@code @Lazy}，避免启动期的循环依赖。
 */
package com.github.zhizi01.ets.api;
