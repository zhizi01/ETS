package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingSession;

import java.util.List;

/** 模拟盘与交易场次的查询。实现方：模拟盘模块。 */
public interface SimPort {

    Sim findSim(String simId);

    /** 找不到抛 SIM_NOT_FOUND */
    Sim requireSim(String simId);

    /** 校验模拟盘处于 RUNNING，否则抛 SIM_NOT_RUNNING */
    Sim requireRunning(String simId);

    List<Sim> listAll();

    List<Sim> listRunning();

    TradingSession findSession(String sessionId);

    /** 找不到抛 SESSION_NOT_FOUND */
    TradingSession requireSession(String sessionId);

    List<TradingSession> listSessions(String simId);

    /** 该模拟盘下当前可接单的场次 */
    List<TradingSession> listOpenSessions(String simId);

    void saveSession(TradingSession session);
}
