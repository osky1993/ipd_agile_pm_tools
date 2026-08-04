package com.ipd.toolbox.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;
    private final long expireMillis;

    public JwtService(@Value("${ipd.jwt.secret}") String secret,
                      @Value("${ipd.jwt.expire-minutes}") long expireMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMillis = expireMinutes * 60_000L;
    }

    public String generate(Long userId, String username, List<String> roles) {
        return generate(userId, username, roles, expireMillis);
    }

    /** 指定有效期签发（长效 API token 用，如 MCP 对接）。 */
    public String generate(Long userId, String username, List<String> roles, long ttlMillis) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("roles", roles)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    @SuppressWarnings("unchecked")
    public UserPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).getPayload();
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        List<String> roles = claims.get("roles", List.class);
        return new UserPrincipal(userId, username, roles);
    }
}
