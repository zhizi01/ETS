package com.github.zhizi01.ets.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.*;

@Configuration
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
public class AsyncConfig {

    private final EtsProperties props;

    /** 撮合执行器：同一场次串行、不同场次并行，由调度器按 sessionId 分片派发。 */
    @Bean(name = "matchExecutor", destroyMethod = "shutdown")
    public ExecutorService matchExecutor() {
        return Executors.newFixedThreadPool(
                Math.max(props.getEngine().getMatchThreads(), 1),
                namedFactory("ets-match-"));
    }

    /** 机器人 tick 调度器。 */
    @Bean(name = "robotScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService robotScheduler() {
        return Executors.newScheduledThreadPool(
                Math.max(props.getRobot().getThreads(), 1),
                namedFactory("ets-robot-"));
    }

    /** 行情推送与 K 线聚合。 */
    @Bean(name = "marketScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService marketScheduler() {
        return Executors.newScheduledThreadPool(2, namedFactory("ets-market-"));
    }

    /**
     * 统一出清专用。日前现货要逐个跑 24 个小时段、每段又有几十笔成交要落 Redis，
     * 整场可能耗时以分钟计，必须与场次时钟和 HTTP 请求线程隔离——
     * 否则一次出清就会把整个时钟卡住，全部模拟盘的场次都不再开闭市。
     */
    @Bean(name = "clearingExecutor", destroyMethod = "shutdown")
    public ExecutorService clearingExecutor() {
        return Executors.newFixedThreadPool(2, namedFactory("ets-clearing-"));
    }

    /** 场次状态机与结算等定时任务。 */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(3);
        s.setThreadNamePrefix("ets-sched-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(10);
        return s;
    }

    private ThreadFactory namedFactory(String prefix) {
        return new ThreadFactory() {
            private final ThreadFactory delegate = Executors.defaultThreadFactory();
            private int n = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = delegate.newThread(r);
                t.setName(prefix + (++n));
                t.setDaemon(true);
                return t;
            }
        };
    }
}
