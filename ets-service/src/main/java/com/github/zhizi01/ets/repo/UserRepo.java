package com.github.zhizi01.ets.repo;

import com.github.zhizi01.ets.domain.model.User;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserRepo extends BaseRepo<User> {

    public UserRepo(RedisStore store) {
        super(store, User.class);
    }

    @Override
    protected String key(String id) {
        return RedisKeys.user(id);
    }

    public void saveUser(User u) {
        save(u.getId(), u);
        store.hSet(RedisKeys.idxUsername(), u.getUsername(), u.getId());
        store.zAdd(RedisKeys.userAll(), u.getId(), u.getCreatedAt());
    }

    public User findByUsername(String username) {
        String id = store.hGet(RedisKeys.idxUsername(), username);
        return id == null ? null : find(id);
    }

    public boolean usernameExists(String username) {
        return store.hGet(RedisKeys.idxUsername(), username) != null;
    }

    /** 全部用户，注册时间倒序 */
    public List<User> listAll() {
        return findAll(store.zRevRangeAll(RedisKeys.userAll()));
    }

    public long count() {
        return store.zCard(RedisKeys.userAll());
    }
}
