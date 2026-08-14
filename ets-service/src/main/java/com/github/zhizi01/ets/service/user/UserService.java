package com.github.zhizi01.ets.service.user;

import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.domain.dto.AuthRegisterReq;
import com.github.zhizi01.ets.domain.enums.Role;
import com.github.zhizi01.ets.domain.model.Admin;
import com.github.zhizi01.ets.domain.model.User;
import com.github.zhizi01.ets.repo.AdminRepo;
import com.github.zhizi01.ets.repo.UserRepo;
import com.github.zhizi01.ets.security.PasswordUtil;
import com.github.zhizi01.ets.security.TokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/** 交易用户与管理员的账号生命周期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepo userRepo;
    private final AdminRepo adminRepo;
    private final TokenService tokenService;
    private final IdGen idGen;

    // ── 注册与登录 ──────────────────────────────────────────

    public User register(AuthRegisterReq req) {
        String username = req.getUsername().trim();
        if (userRepo.usernameExists(username)) {
            throw BizException.of(ErrorCode.USERNAME_EXISTS);
        }
        User u = new User();
        u.setId(idGen.next(IdGen.USER));
        u.setUsername(username);
        u.setPassword(PasswordUtil.hash(req.getPassword()));
        u.setNickname(blankTo(req.getNickname(), username));
        u.setOrgName(req.getOrgName());
        u.setPhone(req.getPhone());
        u.setRole(Role.USER);
        u.setCreatedAt(System.currentTimeMillis());
        userRepo.saveUser(u);
        log.info("新用户注册成功 {} / {}", u.getId(), u.getUsername());
        return u;
    }

    /** 校验口令并刷新最后登录时间，失败一律返回 LOGIN_FAILED 以免暴露用户名是否存在。 */
    public User login(String username, String rawPassword) {
        User u = userRepo.findByUsername(username == null ? null : username.trim());
        if (u == null || !PasswordUtil.matches(rawPassword, u.getPassword())) {
            throw BizException.of(ErrorCode.LOGIN_FAILED);
        }
        if (u.isFrozen()) {
            throw BizException.of(ErrorCode.USER_FROZEN);
        }
        u.setLastLoginAt(System.currentTimeMillis());
        userRepo.saveUser(u);
        return u;
    }

    public Admin adminLogin(String username, String rawPassword) {
        Admin a = adminRepo.findByUsername(username == null ? null : username.trim());
        if (a == null || !PasswordUtil.matches(rawPassword, a.getPassword())) {
            throw BizException.of(ErrorCode.LOGIN_FAILED);
        }
        a.setLastLoginAt(System.currentTimeMillis());
        adminRepo.saveAdmin(a);
        return a;
    }

    public String issueToken(User u) {
        return tokenService.issue(u.getId(), u.getUsername(), u.getNickname(), Role.USER);
    }

    public String issueToken(Admin a) {
        return tokenService.issue(a.getId(), a.getUsername(), a.getNickname(), Role.ADMIN);
    }

    // ── 查询 ────────────────────────────────────────────────

    public User require(String userId) {
        return userRepo.require(userId, ErrorCode.NOT_FOUND);
    }

    /** 关键词匹配用户名 / 昵称 / 单位，留空返回全部（注册时间倒序）。 */
    public List<User> search(String keyword) {
        List<User> all = userRepo.listAll();
        if (keyword == null || keyword.isBlank()) {
            return all;
        }
        String kw = keyword.trim().toLowerCase();
        List<User> out = new ArrayList<>();
        for (User u : all) {
            if (contains(u.getUsername(), kw) || contains(u.getNickname(), kw) || contains(u.getOrgName(), kw)) {
                out.add(u);
            }
        }
        return out;
    }

    // ── 口令与状态 ──────────────────────────────────────────

    public void changePassword(String userId, String oldPassword, String newPassword) {
        User u = require(userId);
        if (!PasswordUtil.matches(oldPassword, u.getPassword())) {
            throw BizException.of(ErrorCode.OLD_PASSWORD_WRONG);
        }
        u.setPassword(PasswordUtil.hash(newPassword));
        userRepo.saveUser(u);
        // 改密后旧令牌立即失效，强制在所有终端重新登录
        tokenService.revokeAll(userId);
    }

    /** 管理员重置口令，返回实际生效的明文口令供管理端展示一次。未指定时随机生成，不复用任何固定口令。 */
    public String resetPassword(String userId, String newPassword) {
        User u = require(userId);
        String pwd = (newPassword == null || newPassword.isBlank())
                ? randomPassword()
                : newPassword;
        u.setPassword(PasswordUtil.hash(pwd));
        userRepo.saveUser(u);
        tokenService.revokeAll(userId);
        log.info("管理员重置了用户 {} 的登录口令", u.getUsername());
        return pwd;
    }

    public User setFrozen(String userId, boolean frozen) {
        User u = require(userId);
        u.setFrozen(frozen);
        userRepo.saveUser(u);
        if (frozen) {
            // 冻结必须立刻踢下线，否则已持有的令牌仍能继续交易
            tokenService.revokeAll(userId);
        }
        log.info("用户 {} 已{}", u.getUsername(), frozen ? "冻结" : "解冻");
        return u;
    }

    // ── 管理员 ──────────────────────────────────────────────

    public Admin createAdmin(String username, String rawPassword, String nickname, boolean superAdmin) {
        Admin a = new Admin();
        a.setId(idGen.next(IdGen.ADMIN));
        a.setUsername(username);
        a.setPassword(PasswordUtil.hash(rawPassword));
        a.setNickname(blankTo(nickname, username));
        a.setSuperAdmin(superAdmin);
        a.setCreatedAt(System.currentTimeMillis());
        adminRepo.saveAdmin(a);
        return a;
    }

    public Admin findAdmin(String adminId) {
        return adminRepo.find(adminId);
    }

    public Admin findAdminByUsername(String username) {
        return adminRepo.findByUsername(username);
    }

    /** 去掉了容易读错的 0/O/1/l/I，管理员需要口头或书面转达这个口令。 */
    private static String randomPassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static boolean contains(String v, String kw) {
        return v != null && v.toLowerCase().contains(kw);
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
