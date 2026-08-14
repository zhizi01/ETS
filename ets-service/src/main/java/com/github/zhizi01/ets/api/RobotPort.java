package com.github.zhizi01.ets.api;

/** 机器人引擎。实现方：机器人模块。 */
public interface RobotPort {

    /**
     * 为模拟盘批量生成一组默认机器人（含各自的交易账户），返回生成数量。
     * 已存在机器人时直接返回现有数量，不重复生成。
     */
    int spawnDefaultRobots(String simId);

    /** 模拟盘开盘/恢复时启动该盘的全部机器人 */
    void startSim(String simId);

    /** 暂停/结束时停摆 */
    void stopSim(String simId);

    /** 单个机器人启停 */
    void setEnabled(String robotId, boolean enabled);

    /** 该模拟盘当前在跑的机器人数 */
    int runningCount(String simId);
}
