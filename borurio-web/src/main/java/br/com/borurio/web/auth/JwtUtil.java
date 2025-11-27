package br.com.borurio.web.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.util.Date;
import java.util.function.Function;

/**
 * =============================================================================
 * UTILITÁRIO JWT — Geração, Extração e Validação
 * =============================================================================
 * Função:
 *   - Gerar tokens JWT (HS256)
 *   - Validar expiração / assinatura
 *   - Extrair claims e usuário
 *
 * Segurança:
 *   - Chave Base64 HMAC-SHA256
 *   - Expiração curta (1h)
 *   - Tratamento seguro de erros para evitar 500 (PRD-safe)
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@Component
public class JwtUtil {

    /**
     * Chave Base64 HMAC-SHA256
     * Recomendado mover para variável de ambiente: JWT_SECRET
     */
    private static final String SECRET_KEY =
            "Ym9ydXJpbzEyMy1zZWd1cmFuY2Etand0LXNlY3VyaXR5LXNwcmluZw==";

    /** Tempo padrão de expiração: 1 hora */
    private static final long EXPIRATION_TIME = 1000 * 60 * 60;

    // =========================================================================
    // EXTRAÇÃO DE CLAIMS
    // =========================================================================

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        Claims claims = extractAllClaims(token);
        return (claims != null) ? resolver.apply(claims) : null;
    }

    /**
     * Parser JWT com proteção contra tokens inválidos/expirados.
     * Nunca lança exceção para o filtro JWT.
     */
    private Claims extractAllClaims(String token) {

        if (token == null || token.isBlank()) {
            log.warn("Tentativa de processar token JWT vazio ou nulo.");
            return null;
        }

        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSignKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

        } catch (ExpiredJwtException e) {
            log.warn("Token JWT expirado: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("Token JWT malformado: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("Assinatura JWT inválida: {}", e.getMessage());
        } catch (JwtException e) {
            log.warn("JWT inválido: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Erro inesperado ao extrair claims JWT: {}", e.getMessage(), e);
        }

        return null;
    }

    private Key getSignKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // =========================================================================
    // GERAÇÃO DE TOKEN
    // =========================================================================

    public String generateToken(String username) {

        Date agora = new Date();
        Date exp = new Date(agora.getTime() + EXPIRATION_TIME);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(agora)
                .setExpiration(exp)
                .signWith(getSignKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    // =========================================================================
    // VALIDAÇÃO
    // =========================================================================

    public boolean validateToken(String token, String expectedUsername) {

        try {
            String extracted = extractUsername(token);

            if (extracted == null) {
                log.warn("Token inválido: subject ausente.");
                return false;
            }

            boolean valid = extracted.equals(expectedUsername) && !isTokenExpired(token);

            if (!valid) {
                log.warn("Token JWT inválido ou expirado para usuário {}", expectedUsername);
            }

            return valid;

        } catch (Exception e) {
            log.warn("Falha geral ao validar token JWT: {}", e.getMessage());
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        Date exp = extractClaim(token, Claims::getExpiration);
        return exp == null || exp.before(new Date());
    }
}
