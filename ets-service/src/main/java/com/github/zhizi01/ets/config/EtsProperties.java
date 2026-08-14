package com.github.zhizi01.ets.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ets")
public class EtsProperties {

    private Duration tokenTtl = Duration.ofDays(7);
    private List<String> corsOrigins = new ArrayList<>();

    private Bootstrap bootstrap = new Bootstrap();
    private Engine engine = new Engine();
    private Market market = new Market();
    private Robot robot = new Robot();
    private Mysql mysql = new Mysql();

    @Data
    public static class Bootstrap {
        private String adminUsername = "admin";
        private String adminPassword = "admin123";
        private boolean demoData = true;
    }

    @Data
    public static class Engine {
        private int matchThreads = 4;
        private long mirrorIntervalMs = 200;
    }

    @Data
    public static class Market {
        private long quotePushIntervalMs = 1000;
        private String klineIntervals = "1m,5m,15m,1h,1d";

        public List<String> intervalList() {
            List<String> out = new ArrayList<>();
            for (String s : klineIntervals.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        }
    }

    @Data
    public static class Robot {
        private boolean enabled = true;
        private int threads = 8;
        private long minTickIntervalMs = 500;
    }

    @Data
    public static class Mysql {
        /** MySQL 当前不可达时保持 false，全部数据只走 Redis */
        private boolean enabled = false;
        private String url = "";
        private String username = "root";
        private String password = "";
        private int batchSize = 200;
        private long flushIntervalMs = 5000;
    }
}
