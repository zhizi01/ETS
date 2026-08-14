package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.Admin;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class AdminRepo extends BaseRepo<Admin> {

    public AdminRepo(RedisStore store) {
        super(store, Admin.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.admin(id);
    }

    public void saveAdmin(Admin a) {
        save(a.getId(), a);
        store.hSet(RedisKeys.idxAdminUsername(), a.getUsername(), a.getId());
        store.zAdd(RedisKeys.adminAll(), a.getId(), a.getCreatedAt());
    }

    public Admin findByUsername(String username) {
        String id = store.hGet(RedisKeys.idxAdminUsername(), username);
        return id == null ? null : find(id);
    }

    public List<Admin> listAll() {
        return findAll(store.zRange(RedisKeys.adminAll()));
    }
}
