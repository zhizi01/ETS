package com.github.zhizi01.ets.security;

import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.enums.Role;
import com.github.zhizi01.ets.repo.RedisKeys;
import com.github.zhizi01.ets.repo.RedisStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 无状态令牌：随机 token -> LoginUser JSON，存 Redis 并带 TTL。 */
@Service
@RequiredArgsConstructor
public class TokenService {

    private final RedisStore store;
    private final EtsProperties props;

    public String issue(String id, String username, String nickname, Role role) {
        String token = role.name().toLowerCase() + "_" + UUID.randomUUID().toString().replace("-", "");
        LoginUser lu = new LoginUser(id, username, nickname, role, System.currentTimeMillis());
        store.set(RedisKeys.token(token), lu, props.getTokenTtl());
        store.sAdd(RedisKeys.userTokens(id), token);
        return token;
    }

    public LoginUser resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        LoginUser lu = store.get(RedisKeys.token(token), LoginUser.class);
        if (lu != null) {
            // 滑动续期
            store.expire(RedisKeys.token(token), props.getTokenTtl());
        }
        return lu;
    }

    public void revoke(String token) {
        LoginUser lu = store.get(RedisKeys.token(token), LoginUser.class);
        store.delete(RedisKeys.token(token));
        if (lu != null) {
            store.sRem(RedisKeys.userTokens(lu.getId()), token);
        }
    }

    /** 冻结/改密时踢掉该主体的全部会话 */
    public void revokeAll(String principalId) {
        for (String t : store.sMembers(RedisKeys.userTokens(principalId))) {
            store.delete(RedisKeys.token(t));
        }
        store.delete(RedisKeys.userTokens(principalId));
    }
}
