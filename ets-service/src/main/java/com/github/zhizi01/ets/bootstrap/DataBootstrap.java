package com.github.zhizi01.ets.bootstrap;

import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.dto.AcctCreateReq;
import com.github.zhizi01.ets.domain.dto.AuthRegisterReq;
import com.github.zhizi01.ets.domain.dto.SimCreateReq;
import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.model.Admin;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.domain.model.User;
import com.github.zhizi01.ets.repo.SimRepo;
import com.github.zhizi01.ets.repo.UserRepo;
import com.github.zhizi01.ets.service.account.TradingAccountServiceImpl;
import com.github.zhizi01.ets.service.sim.SessionPresetFactory;
import com.github.zhizi01.ets.service.sim.SimServiceImpl;
import com.github.zhizi01.ets.service.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 启动初始化。全程幂等：内置管理员按用户名判重，演示数据只在「一个模拟盘都没有」时生成，
 * 因此重启、重连 Redis 都不会重复建号。
 * <p>刻意不自动开盘 —— 演示盘停在 SETUP，把「点击开盘」这个动作留给讲解人，
 * 这样才能完整展示 SETUP → 建号 → 注资锁定 → RUNNING 的过程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataBootstrap implements ApplicationRunner {

    private static final String DEMO_SIM_NAME = "2026年度电力市场演练盘";
    private static final String DEMO_PASSWORD = "demo123";

    private final EtsProperties props;
    private final UserService userService;
    private final SimServiceImpl simService;
    private final SessionPresetFactory presetFactory;
    private final TradingAccountServiceImpl accountService;
    private final SimRepo simRepo;
    private final UserRepo userRepo;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Admin admin = ensureAdmin();
            BootstrapReport report = new BootstrapReport();
            report.adminUsername = admin.getUsername();

            if (props.getBootstrap().isDemoData()) {
                ensureDemoData(report);
            }
            printBanner(report);
        } catch (Exception e) {
            log.error("启动初始化失败（Redis 是否可达？），系统仍会启动但可能没有内置账号: {}", e.getMessage(), e);
        }
    }

    // ══════════════════════════ 内置管理员 ══════════════════════════

    private Admin ensureAdmin() {
        String username = props.getBootstrap().getAdminUsername();
        Admin exist = userService.findAdminByUsername(username);
        if (exist != null) {
            return exist;
        }
        Admin admin = userService.createAdmin(username, props.getBootstrap().getAdminPassword(),
                "系统管理员", true);
        log.info("已创建内置管理员 {}", admin.getUsername());
        return admin;
    }

    // ══════════════════════════ 演示数据 ══════════════════════════

    private void ensureDemoData(BootstrapReport report) {
        List<Sim> sims = simRepo.listAll();
        if (!sims.isEmpty()) {
            Sim first = sims.get(0);
            report.simId = first.getId();
            report.simName = first.getName();
            report.sessionCount = simService.listSessions(first.getId()).size();
            report.skippedDemo = true;
            return;
        }

        SimCreateReq simReq = new SimCreateReq();
        simReq.setName(DEMO_SIM_NAME);
        simReq.setDescription("内置演示演练场：覆盖年度/月度/月内多日/日前现货四个品种与四种交易方式，"
                + "开盘后自动按真实时间推进场次状态机。");
        simReq.setBaseDate(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
        Sim sim = simService.create(simReq, null);
        report.simId = sim.getId();
        report.simName = sim.getName();

        List<TradingSession> sessions = presetFactory.generateStandard(sim.getId());
        report.sessionCount = sessions.size();

        User demo1 = ensureUser("demo1", "示范发电集团", "华东示范发电集团");
        User demo2 = ensureUser("demo2", "阳光售电", "阳光售电有限公司");
        report.demoUsers = List.of(demo1.getUsername(), demo2.getUsername());

        // demo1：一家煤电厂 + 一家大工业用户，覆盖发用两端
        createAccount(demo1, sim.getId(), "华能滨海电厂", MemberType.GENCO, coalPlant());
        createAccount(demo1, sim.getId(), "东海化工集团", MemberType.CONSUMER, industrialConsumer());
        // demo2：售电公司 + 新能源场站，覆盖代理零售与低成本电源
        createAccount(demo2, sim.getId(), "阳光售电有限公司", MemberType.RETAILER, retailer());
        createAccount(demo2, sim.getId(), "龙源沿海风电场", MemberType.GENCO, windFarm());
        report.accountCount = 4;
    }

    private User ensureUser(String username, String nickname, String orgName) {
        User exist = userRepo.findByUsername(username);
        if (exist != null) {
            return exist;
        }
        AuthRegisterReq req = new AuthRegisterReq();
        req.setUsername(username);
        req.setPassword(DEMO_PASSWORD);
        req.setNickname(nickname);
        req.setOrgName(orgName);
        return userService.register(req);
    }

    private void createAccount(User owner, String simId, String name, MemberType type, AccountParams params) {
        AcctCreateReq req = new AcctCreateReq();
        req.setSimId(simId);
        req.setName(name);
        req.setMemberType(type);
        req.setParams(params);
        accountService.create(owner.getId(), req);
    }

    // ── 演示账户参数：数量级贴近真实的省级电力市场主体 ──

    private static AccountParams coalPlant() {
        AccountParams p = new AccountParams();
        p.setInitialCapital(80_000_000);
        p.setGenType(GenType.COAL);
        p.setInstalledCapacityMw(600d);
        p.setAnnualEnergyMwh(2_700_000d);
        p.setVariableCostPerMwh(320d);
        p.setMinOutputRatio(0.4);
        p.setMaxOutputRatio(1.0);
        p.setForcedOutageRate(0.03);
        return p;
    }

    private static AccountParams windFarm() {
        AccountParams p = new AccountParams();
        p.setInitialCapital(40_000_000);
        p.setGenType(GenType.WIND);
        p.setInstalledCapacityMw(200d);
        p.setAnnualEnergyMwh(440_000d);
        p.setVariableCostPerMwh(60d);
        p.setMinOutputRatio(0.0);
        p.setMaxOutputRatio(1.0);
        p.setForcedOutageRate(0.05);
        return p;
    }

    private static AccountParams industrialConsumer() {
        AccountParams p = new AccountParams();
        p.setInitialCapital(60_000_000);
        p.setAnnualDemandMwh(900_000d);
        p.setMaxAcceptPricePerMwh(440d);
        p.setLoadProfile(LoadProfile.CONTINUOUS);
        p.setRetailBenchmarkPrice(465d);
        return p;
    }

    private static AccountParams retailer() {
        AccountParams p = new AccountParams();
        p.setInitialCapital(70_000_000);
        p.setAgentDemandMwh(1_200_000d);
        p.setAgentUserCount(800);
        p.setRetailPricePerMwh(432d);
        return p;
    }

    // ══════════════════════════ 启动横幅 ══════════════════════════

    private void printBanner(BootstrapReport r) {
        StringBuilder sb = new StringBuilder("\n");
        sb.append("══════════════════ 电力交易模拟器 ETS 已就绪 ══════════════════\n");
        sb.append("  管理端账号 : ").append(r.adminUsername)
                .append(" / ").append(props.getBootstrap().getAdminPassword()).append("\n");
        if (r.demoUsers != null && !r.demoUsers.isEmpty()) {
            sb.append("  演示用户   : ");
            for (int i = 0; i < r.demoUsers.size(); i++) {
                sb.append(r.demoUsers.get(i)).append(" / ").append(DEMO_PASSWORD);
                if (i < r.demoUsers.size() - 1) {
                    sb.append("，");
                }
            }
            sb.append("\n");
            sb.append("  演示账户   : 共 ").append(r.accountCount).append(" 个交易账户（发电/用电/售电三类主体）\n");
        }
        if (r.simId != null) {
            sb.append("  演示模拟盘 : ").append(r.simId).append("  ").append(r.simName).append("\n");
            sb.append("  交易场次   : ").append(r.sessionCount).append(" 个");
            if (r.skippedDemo) {
                sb.append("（已存在模拟盘，本次未重复生成演示数据）");
            }
            sb.append("\n");
            sb.append("  下一步     : 用管理端开盘（POST /api/admin/sims/")
                    .append(r.simId).append("/start），开盘后场次将按真实时间自动开闭市\n");
        }
        sb.append("═══════════════════════════════════════════════════════════════");
        log.info(sb.toString());
    }

    private static class BootstrapReport {
        String adminUsername;
        String simId;
        String simName;
        int sessionCount;
        int accountCount;
        List<String> demoUsers;
        boolean skippedDemo;
    }
}
