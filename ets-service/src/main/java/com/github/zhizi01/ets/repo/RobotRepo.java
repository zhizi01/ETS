package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Robot;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class RobotRepo extends BaseRepo<Robot> {

    public RobotRepo(RedisStore store) {
        super(store, Robot.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.robot(id);
    }

    public void saveRobot(Robot r) {
        save(r.getId(), r);
        store.sAdd(RedisKeys.simRobots(r.getSimId()), r.getId());
    }

    public void removeRobot(Robot r) {
        remove(r.getId());
        store.sRem(RedisKeys.simRobots(r.getSimId()), r.getId());
    }

    public List<Robot> listBySim(String simId) {
        return findAll(store.sMembers(RedisKeys.simRobots(simId)));
    }

    public long countBySim(String simId) {
        return store.sCard(RedisKeys.simRobots(simId));
    }
}
