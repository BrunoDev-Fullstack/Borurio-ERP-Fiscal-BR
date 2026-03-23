package br.com.borurio.web.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.function.Function;

@Slf4j
@Component
public class JwtUtil {

    @Value("${security.jwt.secret}")
    private String secretKey;

    @Value("${security.jwt.expiration-ms:3600000}")
    private long expirationTime;

    private Key key;

    @PostConstruct
    public void init() {
        try {
            byte[] keyBytes;

            // Tenta Base64 primeiro
            try {
                keyBytes = Decoders.BASE64.decode(secretKey);
                log.info("[JWT] Secret interpretado como Base64");
            } catch (Exception e) {
                // fallback para string normal
                log.warn("[JWT] Secret não é Base64 válido, usando como string raw");
                keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            }

            if (keyBytes.length < 32) {
                throw new IllegalArgumentException(
                        "JWT_SECRET inválido: mínimo de 32 bytes requerido para HS256"
                );
            }

            this.key = Keys.hmacShaKeyFor(keyBytes);

            log.info("[JWT] Chave carregada com sucesso. Tamanho: {} bytes", keyBytes.length);

        } catch (Exception e) {
            log.error("[JWT] Erro ao inicializar chave JWT", e);
            throw new IllegalStateException("Falha na configuração do JWT_SECRET", e);
        }
    }

    private Key getSignKey() {
        return key;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(getSignKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationTime);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getSignKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean validateToken(String token, String username) {
        try {
            return extractUsername(token).equals(username)
                    && extractClaim(token, Claims::getExpiration).after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JWT] Token inválido: {}", e.getMessage());
            return false;
        }
    }
}