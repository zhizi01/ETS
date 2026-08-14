package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.RobotBatchReq;
import com.github.zhizi01.ets.domain.dto.RobotCreateReq;
import com.github.zhizi01.ets.domain.dto.RobotStrategyVO;
import com.github.zhizi01.ets.domain.dto.RobotUpdateReq;
import com.github.zhizi01.ets.domain.dto.RobotVO;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.robot.RobotEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 管理端机器人管理，对应 docs/API.md 3.3。 */
@RestController
@RequestMapping("/api/admin/robots")
public class AdminRobotController {

    private final RobotEngine robotEngine;
    private final AccountPort accountPort;

    public AdminRobotController(RobotEngine robotEngine, @Lazy AccountPort accountPort) {
        this.robotEngine = robotEngine;
        this.accountPort = accountPort;
    }

    @GetMapping
    public R<PageResult<RobotVO>> list(@RequestParam String simId,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        List<Robot> robots = robotEngine.listBySim(simId);
        robots.sort(Comparator.comparing(Robot::getId));
        List<RobotVO> vos = new ArrayList<>(robots.size());
        for (Robot r : robots) {
            vos.add(RobotVO.of(r, safeAccount(r.getAccountId()), robotEngine.isRunning(r.getId())));
        }
        return R.ok(PageResult.slice(vos, page, size));
    }

    /** 六种策略的中文名、说明与默认参数，供管理端表单预填 */
    @GetMapping("/strategies")
    public R<List<RobotStrategyVO>> strategies() {
        List<RobotStrategyVO> out = new ArrayList<>();
        for (RobotStrategy s : RobotStrategy.values()) {
            out.add(new RobotStrategyVO(s.name(), s.getLabel(), s.getDescription(),
                    robotEngine.suitableMemberTypes(s), robotEngine.defaultCfg(s)));
        }
        return R.ok(out);
    }

    @PostMapping
    public R<RobotVO> create(@RequestBody RobotCreateReq req) {
        Robot r = robotEngine.create(req);
        return R.ok(RobotVO.of(r, safeAccount(r.getAccountId()), robotEngine.isRunning(r.getId())));
    }

    @PostMapping("/batch")
    public R<Map<String, Object>> batch(@RequestBody RobotBatchReq req) {
        int created = robotEngine.batch(req);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("created", created);
        data.put("total", robotEngine.listBySim(req.getSimId()).size());
        data.put("running", robotEngine.runningCount(req.getSimId()));
        return R.ok(data);
    }

    /** 一键把该盘的默认机器人阵容补齐（已有机器人时不重复生成） */
    @PostMapping("/spawn-default")
    public R<Map<String, Object>> spawnDefault(@RequestParam String simId) {
        int n = robotEngine.spawnDefaultRobots(simId);
        robotEngine.startSim(simId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", n);
        data.put("running", robotEngine.runningCount(simId));
        return R.ok(data);
    }

    @PutMapping("/{id}")
    public R<RobotVO> update(@PathVariable String id, @RequestBody RobotUpdateReq req) {
        Robot r = robotEngine.update(id, req);
        return R.ok(RobotVO.of(r, safeAccount(r.getAccountId()), robotEngine.isRunning(r.getId())));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        robotEngine.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/enable")
    public R<Void> enable(@PathVariable String id) {
        robotEngine.setEnabled(id, true);
        return R.ok();
    }

    @PostMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        robotEngine.setEnabled(id, false);
        return R.ok();
    }

    @PostMapping("/enable-all")
    public R<Map<String, Object>> enableAll(@RequestBody Map<String, String> body) {
        return R.ok(toggleAll(body.get("simId"), true));
    }

    @PostMapping("/disable-all")
    public R<Map<String, Object>> disableAll(@RequestBody Map<String, String> body) {
        return R.ok(toggleAll(body.get("simId"), false));
    }

    private Map<String, Object> toggleAll(String simId, boolean enabled) {
        int affected = robotEngine.setAll(simId, enabled);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("affected", affected);
        data.put("running", robotEngine.runningCount(simId));
        return data;
    }

    /** 账户模块还没就绪时不能让列表接口整个失败 */
    private com.github.zhizi01.ets.domain.model.TradingAccount safeAccount(String accountId) {
        try {
            return accountPort.find(accountId);
        } catch (Exception e) {
            return null;
        }
    }
}
