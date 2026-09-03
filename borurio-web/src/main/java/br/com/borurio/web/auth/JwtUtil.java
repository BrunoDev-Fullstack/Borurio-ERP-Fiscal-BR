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
import java.time.LocalDateTime;
import java.time.ZoneId;
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
        return generateToken(username, null);
    }

    public String generateToken(String username, Long empresaId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationTime);

        var builder = Jwts.builder()
                .setSubject(username)
                .setIssuedAt(now)
                .setExpiration(expiry);

        if (empresaId != null) {
            builder.claim("eid", empresaId);
        }

        return builder.signWith(getSignKey(), SignatureAlgorithm.HS256).compact();
    }

    public Long extractEmpresaId(String token) {
        try {
            Object eid = extractClaim(token, claims -> claims.get("eid"));
            if (eid == null) return null;
            if (eid instanceof Long l) return l;
            if (eid instanceof Integer i) return i.longValue();
            return Long.parseLong(eid.toString());
        } catch (Exception e) {
            return null;
        }
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

    // -------------------------------------------------------------------------
    // OMS — token técnico fiscal
    // -------------------------------------------------------------------------

    /**
     * Gera token JWT para sessão OMS.
     * sub     = codigoOms (identificador da empresa no sistema OMS)
     * eid     = empresaId (id interno da empresa no Borurio)
     * tipo    = "OMS"
     * jti     = UUID fornecido pelo serviço (base para revogação)
     * exp     = not_after do certificado A1 (nunca além da validade do cert)
     */
    public String generateOmsToken(String codigoOms, Long empresaId, String jti, LocalDateTime certNotAfter) {
        return generateOmsToken(codigoOms, empresaId, jti, certNotAfter, null);
    }

    public String generateOmsToken(String codigoOms, Long empresaId, String jti,
                                    LocalDateTime certNotAfter, LocalDateTime issuedAt) {
        Date expiry = Date.from(certNotAfter.atZone(ZoneId.systemDefault()).toInstant());
        Date iat    = issuedAt != null
                ? Date.from(issuedAt.atZone(ZoneId.systemDefault()).toInstant())
                : new Date();
        return Jwts.builder()
                .setSubject(codigoOms)
                .setId(jti)
                .setIssuedAt(iat)
                .setExpiration(expiry)
                .claim("eid",  empresaId)
                .claim("tipo", "OMS")
                .signWith(getSignKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    public String extractTipo(String token) {
        try {
            Object tipo = extractClaim(token, claims -> claims.get("tipo"));
            return tipo != null ? tipo.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public String extractJti(String token) {
        try {
            return extractClaim(token, Claims::getId);
        } catch (Exception e) {
            return null;
        }
    }
}