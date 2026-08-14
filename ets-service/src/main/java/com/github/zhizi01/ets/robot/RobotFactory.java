package com.github.zhizi01.ets.robot;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.RobotRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 机器人与其交易账户的装配厂。
 * <p>
 * 默认阵容按真实电力市场的参与者结构搭配：发电侧覆盖煤气水风光核六种机组、
 * 用电侧覆盖五种负荷特性、售电公司居中做市与套利，
 * 这样开盘后既有成本差异形成的天然供需曲线，也有做市与噪声提供的流动性。
 */
@Slf4j
@Component
public class RobotFactory {

    /** 各机组类型的年利用小时数，用来推算年度可发电量 */
    private static final Map<GenType, Integer> UTIL_HOURS = Map.of(
            GenType.COAL, 4500,
            GenType.GAS, 2600,
            GenType.HYDRO, 3600,
            GenType.WIND, 2200,
            GenType.SOLAR, 1300,
            GenType.NUCLEAR, 7500,
            GenType.STORAGE, 1200);

    private final AccountPort accountPort;
    private final RobotRepo robotRepo;
    private final IdGen idGen;

    public RobotFactory(@Lazy AccountPort accountPort, RobotRepo robotRepo, IdGen idGen) {
        this.accountPort = accountPort;
        this.robotRepo = robotRepo;
        this.idGen = idGen;
    }

    /** 一台待创建的机器人 */
    public record Spec(MemberType memberType,
                       RobotStrategy strategy,
                       String name,
                       GenType genType,
                       LoadProfile loadProfile,
                       double capital) {
    }

    // ── 默认阵容：24 台 ────────────────────────────────────

    public List<Spec> defaultRoster() {
        List<Spec> list = new ArrayList<>(24);

        // 发电侧 10 台：成本从光伏 50 元一路铺到燃气 430 元，天然形成供给曲线
        list.add(genco("华能燃煤一厂", GenType.COAL, RobotStrategy.COST_BASED, 150_000_000));
        list.add(genco("大唐燃煤二厂", GenType.COAL, RobotStrategy.MARKET_MAKER, 140_000_000));
        list.add(genco("国电双河电厂", GenType.COAL, RobotStrategy.NOISE, 110_000_000));
        list.add(genco("申能燃气电厂", GenType.GAS, RobotStrategy.COST_BASED, 90_000_000));
        list.add(genco("京能燃气热电", GenType.GAS, RobotStrategy.NOISE, 85_000_000));
        list.add(genco("三峡梯级水电", GenType.HYDRO, RobotStrategy.COST_BASED, 180_000_000));
        list.add(genco("龙源风电场", GenType.WIND, RobotStrategy.NOISE, 70_000_000));
        list.add(genco("华电沿海风场", GenType.WIND, RobotStrategy.MARKET_MAKER, 75_000_000));
        list.add(genco("晶科光伏电站", GenType.SOLAR, RobotStrategy.NOISE, 60_000_000));
        list.add(genco("秦岭核电基地", GenType.NUCLEAR, RobotStrategy.COST_BASED, 200_000_000));

        // 用电侧 9 台：五种负荷曲线都有，现货 24 点报价才不会千篇一律
        list.add(consumer("江南铝业", LoadProfile.CONTINUOUS, RobotStrategy.DEMAND, 90_000_000));
        list.add(consumer("宝新钢铁集团", LoadProfile.CONTINUOUS, RobotStrategy.DEMAND, 100_000_000));
        list.add(consumer("中原化工", LoadProfile.THREE_SHIFT, RobotStrategy.TREND, 70_000_000));
        list.add(consumer("东方水泥", LoadProfile.TWO_SHIFT, RobotStrategy.NOISE, 45_000_000));
        list.add(consumer("长虹电子", LoadProfile.TWO_SHIFT, RobotStrategy.DEMAND, 50_000_000));
        list.add(consumer("银河数据中心", LoadProfile.CONTINUOUS, RobotStrategy.DEMAND, 65_000_000));
        list.add(consumer("明珠商业广场", LoadProfile.COMMERCIAL, RobotStrategy.TREND, 35_000_000));
        list.add(consumer("城东居民供电", LoadProfile.RESIDENTIAL, RobotStrategy.NOISE, 30_000_000));
        list.add(consumer("华阳纺织", LoadProfile.THREE_SHIFT, RobotStrategy.COST_BASED, 40_000_000));

        // 售电公司 5 台：做市与套利的主力，负责把三个品种的价格串起来
        list.add(retailer("兴电售电公司", RobotStrategy.MARKET_MAKER, 120_000_000));
        list.add(retailer("中电投售电", RobotStrategy.MARKET_MAKER, 110_000_000));
        list.add(retailer("南方能源服务", RobotStrategy.ARBITRAGE, 95_000_000));
        list.add(retailer("智谷综合能源", RobotStrategy.ARBITRAGE, 90_000_000));
        list.add(retailer("恒信售电服务", RobotStrategy.TREND, 80_000_000));
        return list;
    }

    private Spec genco(String name, GenType t, RobotStrategy s, double capital) {
        return new Spec(MemberType.GENCO, s, name, t, null, capital);
    }

    private Spec consumer(String name, LoadProfile p, RobotStrategy s, double capital) {
        return new Spec(MemberType.CONSUMER, s, name, null, p, capital);
    }

    private Spec retailer(String name, RobotStrategy s, double capital) {
        return new Spec(MemberType.RETAILER, s, name, null, null, capital);
    }

    // ── 创建 ────────────────────────────────────────────────

    public Robot create(Sim sim, Spec spec, RobotConfig cfg, boolean enabled) {
        if (sim == null) {
            throw BizException.of(ErrorCode.SIM_NOT_FOUND);
        }
        Random rnd = ThreadLocalRandom.current();
        String robotId = idGen.next(IdGen.ROBOT);
        double capital = clampCapital(sim, spec.capital() * (0.85 + rnd.nextDouble() * 0.3));

        TradingAccount acct = accountPort.createRobotAccount(
                sim.getId(), spec.memberType(), spec.strategy(), spec.name(), robotId, capital);
        if (acct == null) {
            throw BizException.of(ErrorCode.ACCOUNT_NOT_FOUND, "机器人交易账户创建失败：" + spec.name());
        }
        enrich(sim, acct, spec, capital, rnd);

        Robot r = new Robot();
        r.setId(robotId);
        r.setSimId(sim.getId());
        r.setAccountId(acct.getId());
        r.setAccountCode(acct.getCode());
        r.setName(spec.name());
        r.setMemberType(spec.memberType());
        r.setStrategy(spec.strategy());
        r.setEnabled(enabled);
        r.setCfg(cfg != null ? cfg : defaultCfg(spec.strategy()));
        if (r.getCfg().getRandomSeed() == 0) {
            r.getCfg().setRandomSeed(rnd.nextLong());
        }
        r.setCreatedAt(System.currentTimeMillis());
        r.setUpdatedAt(r.getCreatedAt());
        robotRepo.saveRobot(r);
        log.debug("创建机器人 {} {} {} 账户 {}", r.getId(), r.getName(), r.getStrategy(), acct.getCode());
        return r;
    }

    /**
     * 补齐账户参数。
     * createRobotAccount 由账户模块实现，这里只填它没给或给了空值的字段，
     * 并按机组类型重算变动成本——同类型机组之间也要有成本差异，报价才不会完全重合。
     */
    private void enrich(Sim sim, TradingAccount acct, Spec spec, double capital, Random rnd) {
        AccountParams p = acct.getParams();
        if (p == null) {
            p = new AccountParams();
            acct.setParams(p);
        }
        if (p.getInitialCapital() <= 0) {
            p.setInitialCapital(capital);
        }
        double benchmark = sim.getCfg() != null && sim.getCfg().getBenchmarkPrice() > 0
                ? sim.getCfg().getBenchmarkPrice() : 380;

        switch (spec.memberType()) {
            case GENCO -> {
                GenType g = spec.genType() != null ? spec.genType()
                        : (p.getGenType() != null ? p.getGenType() : GenType.COAL);
                // 账户模块是按策略推的机组类型，这里按阵容指定的类型重算，
                // 否则会出现「光伏电站装了 1000MW 核电机组」这种自相矛盾的参数
                boolean retype = spec.genType() != null && spec.genType() != p.getGenType();
                p.setGenType(g);
                p.setVariableCostPerMwh(Nums.price(g.getTypicalCost() * (0.90 + rnd.nextDouble() * 0.22)));
                if (retype || isBlank(p.getInstalledCapacityMw())) {
                    p.setInstalledCapacityMw(Nums.round(capacityOf(g) * (0.8 + rnd.nextDouble() * 0.5), 0));
                }
                if (retype || isBlank(p.getAnnualEnergyMwh())) {
                    int hours = UTIL_HOURS.getOrDefault(g, 3000);
                    p.setAnnualEnergyMwh(Nums.qty(p.getInstalledCapacityMw() * hours));
                    // 装机变了，按装机推导的风控额度也要跟着重算
                    p.setMaxSingleOrderMwh(null);
                    p.setMaxPositionMwh(null);
                }
                if (isBlank(p.getMinOutputRatio())) {
                    p.setMinOutputRatio(g.isRenewable() ? 0.0 : 0.4);
                }
                if (isBlank(p.getMaxOutputRatio())) {
                    p.setMaxOutputRatio(1.0);
                }
                if (isBlank(p.getForcedOutageRate())) {
                    p.setForcedOutageRate(Nums.round(0.015 + rnd.nextDouble() * 0.045, 4));
                }
                fillRisk(p, p.getAnnualEnergyMwh());
            }
            case CONSUMER -> {
                p.setLoadProfile(spec.loadProfile() != null ? spec.loadProfile()
                        : (p.getLoadProfile() != null ? p.getLoadProfile() : LoadProfile.CONTINUOUS));
                if (isBlank(p.getAnnualDemandMwh())) {
                    p.setAnnualDemandMwh(Nums.qty(200_000 + rnd.nextDouble() * 1_300_000));
                }
                if (isBlank(p.getRetailBenchmarkPrice())) {
                    p.setRetailBenchmarkPrice(Nums.price(benchmark * (1.18 + rnd.nextDouble() * 0.14)));
                }
                if (isBlank(p.getMaxAcceptPricePerMwh())) {
                    p.setMaxAcceptPricePerMwh(Nums.price(benchmark * (1.08 + rnd.nextDouble() * 0.20)));
                }
                fillRisk(p, p.getAnnualDemandMwh());
            }
            case RETAILER -> {
                if (isBlank(p.getAgentDemandMwh())) {
                    p.setAgentDemandMwh(Nums.qty(400_000 + rnd.nextDouble() * 1_600_000));
                }
                if (p.getAgentUserCount() == null || p.getAgentUserCount() <= 0) {
                    p.setAgentUserCount(200 + rnd.nextInt(3000));
                }
                if (isBlank(p.getRetailPricePerMwh())) {
                    p.setRetailPricePerMwh(Nums.price(benchmark * (1.06 + rnd.nextDouble() * 0.16)));
                }
                if (isBlank(p.getMaxAcceptPricePerMwh())) {
                    p.setMaxAcceptPricePerMwh(p.getRetailPricePerMwh());
                }
                // 不给售电公司凭空的可售电量：按风控约定它只能转售已购入的部分，
                // 所以它的做市卖单要等买到货之后才报得出来，这符合真实的售电业务
                fillRisk(p, p.getAgentDemandMwh());
            }
            default -> {
                // MemberType 只有三种，这里不会走到
            }
        }
        try {
            accountPort.save(acct);
        } catch (Exception e) {
            log.warn("回写机器人账户参数失败 {}: {}", acct.getId(), e.getMessage());
        }
    }

    private void fillRisk(AccountParams p, Double scale) {
        double s = scale == null || scale <= 0 ? 500_000 : scale;
        if (isBlank(p.getMaxSingleOrderMwh())) {
            p.setMaxSingleOrderMwh(Nums.qty(Math.max(s * 0.05, 5_000)));
        }
        if (isBlank(p.getMaxPositionMwh())) {
            p.setMaxPositionMwh(Nums.qty(s));
        }
        if (p.getRiskAppetite() == null || p.getRiskAppetite() < 1 || p.getRiskAppetite() > 5) {
            p.setRiskAppetite(3);
        }
    }

    private boolean isBlank(Double v) {
        return v == null || v <= 0;
    }

    /** 各类机组的典型单厂装机规模 MW */
    private double capacityOf(GenType g) {
        return switch (g) {
            case COAL -> 660;
            case GAS -> 390;
            case HYDRO -> 300;
            case WIND -> 200;
            case SOLAR -> 150;
            case NUCLEAR -> 1000;
            case STORAGE -> 100;
        };
    }

    private double clampCapital(Sim sim, double capital) {
        double lo = 1_000_000;
        double hi = 500_000_000;
        if (sim.getCfg() != null) {
            if (sim.getCfg().getInitialCapitalMin() > 0) {
                lo = sim.getCfg().getInitialCapitalMin();
            }
            if (sim.getCfg().getInitialCapitalMax() > 0) {
                hi = sim.getCfg().getInitialCapitalMax();
            }
        }
        if (hi < lo) {
            hi = lo;
        }
        return Nums.amount(Nums.clamp(capital, lo, hi));
    }

    // ── 策略默认参数 ────────────────────────────────────────

    /** 每种策略的行为节奏不同：做市要快而密，套利要慢而准 */
    public RobotConfig defaultCfg(RobotStrategy strategy) {
        RobotConfig c = new RobotConfig();
        if (strategy == null) {
            return c;
        }
        switch (strategy) {
            case MARKET_MAKER -> {
                c.setTickIntervalMs(2500);
                c.setAggressiveness(0.45);
                c.setMaxOrderMwh(4000);
                c.setSpreadPct(0.010);
                c.setInventoryTargetMwh(0);
                c.setActProbability(0.85);
                c.setCancelProbability(0.35);
                c.setListingProbability(0.15);
            }
            case TREND -> {
                c.setTickIntervalMs(3500);
                c.setAggressiveness(0.60);
                c.setMaxOrderMwh(3500);
                c.setSpreadPct(0.014);
                c.setActProbability(0.65);
                c.setCancelProbability(0.20);
                c.setListingProbability(0.20);
            }
            case COST_BASED -> {
                c.setTickIntervalMs(4000);
                c.setAggressiveness(0.35);
                c.setMaxOrderMwh(6000);
                c.setSpreadPct(0.012);
                c.setActProbability(0.70);
                c.setCancelProbability(0.15);
                c.setListingProbability(0.30);
            }
            case NOISE -> {
                c.setTickIntervalMs(2000);
                c.setAggressiveness(0.50);
                c.setMaxOrderMwh(2500);
                c.setSpreadPct(0.016);
                c.setActProbability(0.60);
                c.setCancelProbability(0.30);
                c.setListingProbability(0.12);
            }
            case ARBITRAGE -> {
                c.setTickIntervalMs(5000);
                c.setAggressiveness(0.70);
                c.setMaxOrderMwh(4500);
                c.setSpreadPct(0.010);
                c.setActProbability(0.55);
                c.setCancelProbability(0.20);
                c.setListingProbability(0.25);
            }
            case DEMAND -> {
                c.setTickIntervalMs(4500);
                c.setAggressiveness(0.55);
                c.setMaxOrderMwh(5000);
                c.setSpreadPct(0.012);
                c.setActProbability(0.70);
                c.setCancelProbability(0.10);
                c.setListingProbability(0.22);
            }
            default -> {
                // 枚举已穷举
            }
        }
        return c;
    }

    /** 该策略适合挂在哪些主体上，供管理端表单联动 */
    public List<String> suitableMemberTypes(RobotStrategy strategy) {
        if (strategy == null) {
            return List.of();
        }
        return switch (strategy) {
            case COST_BASED -> List.of(MemberType.GENCO.name(), MemberType.CONSUMER.name());
            case DEMAND -> List.of(MemberType.CONSUMER.name(), MemberType.RETAILER.name(), MemberType.GENCO.name());
            case ARBITRAGE -> List.of(MemberType.RETAILER.name(), MemberType.GENCO.name());
            default -> List.of(MemberType.GENCO.name(), MemberType.CONSUMER.name(), MemberType.RETAILER.name());
        };
    }

    /** 主体类型的默认注资规模 */
    public double defaultCapital(MemberType type) {
        if (type == null) {
            return 80_000_000;
        }
        return switch (type) {
            case GENCO -> 120_000_000;
            case CONSUMER -> 60_000_000;
            case RETAILER -> 100_000_000;
        };
    }

    /** 按配比展开出一批 Spec，供 /robots/batch 使用 */
    public List<Spec> expand(int count, Map<String, Integer> strategyMix, Map<String, Integer> memberTypeMix,
                             double capitalMin, double capitalMax, int nameOffset) {
        List<RobotStrategy> strategies = expandMix(strategyMix, RobotStrategy.class);
        List<MemberType> members = expandMix(memberTypeMix, MemberType.class);
        int total = count > 0 ? count : Math.max(Math.max(strategies.size(), members.size()), 1);

        Random rnd = ThreadLocalRandom.current();
        List<Spec> out = new ArrayList<>(total);
        GenType[] genTypes = GenType.values();
        LoadProfile[] profiles = LoadProfile.values();
        for (int i = 0; i < total; i++) {
            MemberType mt = members.isEmpty() ? rotateMember(i) : members.get(i % members.size());
            RobotStrategy st = strategies.isEmpty() ? defaultStrategyFor(mt, i) : strategies.get(i % strategies.size());
            double capital = capitalMin > 0 && capitalMax >= capitalMin
                    ? capitalMin + rnd.nextDouble() * (capitalMax - capitalMin)
                    : defaultCapital(mt);
            String name = generatedName(mt, nameOffset + i + 1);
            GenType g = mt == MemberType.GENCO ? genTypes[i % (genTypes.length - 1)] : null;
            LoadProfile lp = mt == MemberType.CONSUMER ? profiles[i % profiles.length] : null;
            out.add(new Spec(mt, st, name, g, lp, capital));
        }
        return out;
    }

    private <E extends Enum<E>> List<E> expandMix(Map<String, Integer> mix, Class<E> type) {
        List<E> out = new ArrayList<>();
        if (mix == null || mix.isEmpty()) {
            return out;
        }
        Map<String, Integer> normalized = new LinkedHashMap<>(mix);
        normalized.forEach((k, v) -> {
            if (k == null || v == null || v <= 0) {
                return;
            }
            try {
                E e = Enum.valueOf(type, k.trim().toUpperCase());
                for (int i = 0; i < v; i++) {
                    out.add(e);
                }
            } catch (IllegalArgumentException ex) {
                log.warn("忽略无法识别的配比键 {}", k);
            }
        });
        return out;
    }

    /** 没给配比时按 10:9:5 的近似节奏轮转，保持结构合理 */
    private MemberType rotateMember(int i) {
        int slot = i % 24;
        if (slot < 10) {
            return MemberType.GENCO;
        }
        return slot < 19 ? MemberType.CONSUMER : MemberType.RETAILER;
    }

    private RobotStrategy defaultStrategyFor(MemberType mt, int i) {
        return switch (mt) {
            case GENCO -> switch (i % 3) {
                case 0 -> RobotStrategy.COST_BASED;
                case 1 -> RobotStrategy.MARKET_MAKER;
                default -> RobotStrategy.NOISE;
            };
            case CONSUMER -> switch (i % 3) {
                case 0 -> RobotStrategy.DEMAND;
                case 1 -> RobotStrategy.TREND;
                default -> RobotStrategy.NOISE;
            };
            case RETAILER -> switch (i % 3) {
                case 0 -> RobotStrategy.MARKET_MAKER;
                case 1 -> RobotStrategy.ARBITRAGE;
                default -> RobotStrategy.TREND;
            };
        };
    }

    private static final String[] GENCO_NAMES = {"华信发电", "东方电力", "北方能源", "远达电厂", "长风电力",
            "恒源发电", "中晟能源", "泰和电力", "锦江发电", "瑞丰能源"};
    private static final String[] CONSUMER_NAMES = {"宏达制造", "新光材料", "金鼎冶炼", "碧海化纤", "启明电子",
            "汇丰机械", "云顶数据", "天成纸业", "五洲玻璃", "安泰重工"};
    private static final String[] RETAILER_NAMES = {"通达售电", "华兴售电", "民生售电", "启元综合能源", "同方售电",
            "国联售电", "鑫源售电", "博远售电"};

    private String generatedName(MemberType mt, int index) {
        String[] pool = switch (mt) {
            case GENCO -> GENCO_NAMES;
            case CONSUMER -> CONSUMER_NAMES;
            case RETAILER -> RETAILER_NAMES;
        };
        String base = pool[(index - 1) % pool.length];
        int round = (index - 1) / pool.length;
        return round == 0 ? base : base + "·" + (round + 1) + "号";
    }
}
