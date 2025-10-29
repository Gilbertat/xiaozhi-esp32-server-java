package com.xiaozhi.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
public class JwtTokenUtil {

    @Value("${jwt.secret:veryLongSecretKeyForHS512Algorithm20250123456789012345678901234567890}")
    private String secret;

    @Value("${jwt.expiration:86400}") // 默认24小时
    private Long expiration;

    // 预计算的密钥，确保长度符合HS512要求
    private SecretKey signingKey;

    public void setSecret(String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
    }
    
    // 通过@PostConstruct初始化
    @jakarta.annotation.PostConstruct
    public void init() {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
    }

    public String generateToken(com.xiaozhi.entity.SysUser userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userDetails.getUserId());
        claims.put("username", userDetails.getUsername());
        return createToken(claims, userDetails.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        Date issuedDate = new Date();
        Date expirationDate = new Date(issuedDate.getTime() + expiration * 1000);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(subject)
                .setIssuedAt(issuedDate)
                .setExpiration(expirationDate)
                .signWith(signingKey, SignatureAlgorithm.HS512)
                .compact();
    }

    public Boolean validateToken(String token, com.xiaozhi.entity.SysUser userDetails) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            String username = claims.getSubject();
            boolean isNotExpired = !isTokenExpired(token);
            
            // 如果userDetails为null，只验证token是否过期
            if (userDetails == null) {
                return username != null && isNotExpired;
            }
            
            // 如果userDetails不为null，验证用户名和过期时间
            return username != null && username.equals(userDetails.getUsername()) && isNotExpired;
        } catch (Exception e) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public Integer getUserIdFromToken(String token) {
        Claims claims = getAllClaimsFromToken(token);
        return (Integer) claims.get("userId");
    }

    public Date getExpirationDateFromToken(String token) {
        return getClaimFromToken(token, Claims::getExpiration);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = getAllClaimsFromToken(token);
        return claimsResolver.apply(claims);
    }

    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private Boolean isTokenExpired(String token) {
        final Date expiration = getExpirationDateFromToken(token);
        return expiration.before(new Date());
    }
}