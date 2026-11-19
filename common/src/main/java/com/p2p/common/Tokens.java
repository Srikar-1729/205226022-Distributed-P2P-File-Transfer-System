package com.p2p.common;

import com.p2p.proto.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Creates and verifies the two kinds of RS256-signed JWTs used in the system:
 * <ul>
 *   <li><b>user tokens</b>, issued by the Auth service, identifying the caller;</li>
 *   <li><b>download tickets</b>, issued by the Catalog service, granting access to one file's swarm and chunks.</li>
 * </ul>
 */
public final class Tokens {
    private static final String TYPE = "typ";
    private static final String USER = "user";
    private static final String TICKET = "ticket";

    private Tokens() {}

    public record Ticket(String userId, String fileId, Instant expiresAt) {}

    public static String issueUserToken(PrivateKey key, Principal p, Instant expiresAt) {
        return Jwts.builder()
                .issuer("auth-service")
                .subject(p.id())
                .claim(TYPE, USER)
                .claim("username", p.username())
                .claim("role", p.role().name())
                .issuedAt(new Date())
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException if the signature, expiry or type is wrong */
    public static Principal parseUserToken(PublicKey key, String token) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        if (!USER.equals(c.get(TYPE, String.class))) {
            throw new JwtException("not a user token");
        }
        return new Principal(c.getSubject(), c.get("username", String.class), Role.valueOf(c.get("role", String.class)));
    }

    public static String issueTicket(PrivateKey key, String userId, String fileId, Duration ttl) {
        return Jwts.builder()
                .issuer("catalog-service")
                .subject(userId)
                .claim(TYPE, TICKET)
                .claim("fileId", fileId)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** @throws JwtException if the signature, expiry or type is wrong */
    public static Ticket parseTicket(PublicKey key, String ticket) {
        Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(ticket).getPayload();
        if (!TICKET.equals(c.get(TYPE, String.class))) {
            throw new JwtException("not a download ticket");
        }
        return new Ticket(c.getSubject(), c.get("fileId", String.class), c.getExpiration().toInstant());
    }

    public static Duration ttl(Instant expiresAt) {
        return Duration.between(Instant.now(), expiresAt);
    }
}
