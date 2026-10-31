package com.knowledge.engine;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.knowledge.engine.Models.Token;
import com.knowledge.engine.Models.User;
import com.knowledge.engine.persistence.TokenMapper;
import com.knowledge.engine.persistence.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Service
public class AuthService {
    private final UserMapper users;
    private final TokenMapper tokens;
    private final TransactionTemplate tx;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final SecureRandom random = new SecureRandom();
    private final long tokenHours;
    public AuthService(UserMapper users, TokenMapper tokens, TransactionTemplate tx,
                       @Value("${app.token-hours}") long tokenHours) {
        this.users = users; this.tokens = tokens; this.tx = tx; this.tokenHours = tokenHours;
    }
    public Map<String, Object> register(String username, String password) {
        validate(username, password);
        User user = new User(); user.id = UUID.randomUUID().toString(); user.username = username.trim();
        user.passwordHash = encoder.encode(password); user.createdAt = Instant.now().toString();
        try { return tx.execute(s -> { users.insert(user); return loginResult(user); }); }
        catch (DuplicateKeyException e) { throw new ApiException(409, "USERNAME_EXISTS", "用户名已存在", false); }
    }
    public Map<String, Object> login(String username, String password) {
        validate(username, password);
        User user = users.selectOne(new QueryWrapper<User>().eq("username", username.trim()));
        if (user == null || !encoder.matches(password, user.passwordHash))
            throw new ApiException(401, "INVALID_CREDENTIALS", "用户名或密码错误", false);
        return loginResult(user);
    }
    public User authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() > 200)
            throw new ApiException(401, "UNAUTHORIZED", "请先登录", false);
        Token token = tokens.selectById(hash(authorization.substring(7)));
        if (token == null || !Instant.parse(token.expiresAt).isAfter(Instant.now()))
            throw new ApiException(401, "UNAUTHORIZED", "登录凭证无效或已过期", false);
        User user = users.selectById(token.userId);
        if (user == null) throw new ApiException(401, "UNAUTHORIZED", "登录凭证无效", false);
        return user;
    }
    private Map<String, Object> loginResult(User user) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Token token = new Token(); token.id = hash(secret); token.userId = user.id;
        token.expiresAt = Instant.now().plusSeconds(tokenHours * 3600).toString(); tokens.insert(token);
        return Map.of("token", secret, "user", view(user));
    }
    public static Map<String, Object> view(User user) {
        return Map.of("id", user.id, "username", user.username, "created_at", user.createdAt);
    }
    private void validate(String username, String password) {
        if (username == null || !username.trim().matches("[\\p{L}\\p{N}_-]{3,50}"))
            throw ApiException.invalid("用户名须为 3–50 位字母、数字、下划线或连字符");
        // BCrypt 只使用前 72 字节，拒绝超长密码以免出现截断等价。
        if (password == null || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw ApiException.invalid("密码至少 8 个字符，UTF-8 长度不超过 72 字节");
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
