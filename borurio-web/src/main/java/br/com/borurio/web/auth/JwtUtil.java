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
 * UTILITÁRIO JWT — GERAÇÃO, VALIDAÇÃO E EXTRAÇÃO DE TOKENS
 * =============================================================================
 * Responsável por toda manipulação segura de tokens JWT na aplicação:
 *   - Geração de tokens (HS256)
 *   - Validação de assinatura e expiração
 *   - Extração de claims e subject (usuário)
 *
 * Boas práticas aplicadas:
 *   - Chave secreta codificada em Base64 (HS256)
 *   - Expiração curta (1 hora) para reduzir risco de replay
 *   - Captura de exceções no parser JWT (para evitar 500 internos)
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
 * =============================================================================
 */
@Slf4j
@Component
public class JwtUtil {

    /**
     * Chave secreta Base64 segura para assinatura HS256.
     * Recomendação: manter variável em ambiente (ex: JWT_SECRET) no futuro.
     */
    private static final String SECRET_KEY =
            "Ym9ydXJpbzEyMy1zZWd1cmFuY2Etand0LXNlY3VyaXR5LXNwcmluZw==";

    /** Tempo padrão de expiração (1 hora = 3600000 ms). */
    private static final long EXPIRATION_TIME = 1000 * 60 * 60;

    // =========================================================================
    // EXTRAÇÃO DE INFORMAÇÕES DO TOKEN
    // =========================================================================

    /**
     * Extrai o nome de usuário (subject) do token.
     *
     * @param token Token JWT
     * @return Username contido no subject
     */
    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extrai uma claim específica do token.
     *
     * @param token           Token JWT
     * @param claimsResolver  Função para resolver a claim desejada
     * @param <T>             Tipo de retorno da claim
     * @return Valor da claim solicitada
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Extrai todas as claims do token (decodificação e validação da assinatura).
     *
     * @param token Token JWT
     * @return Claims decodificadas
     */
    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getSignKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (ExpiredJwtException e) {
            log.warn("Token JWT expirado: {}", e.getMessage());
            throw e;
        } catch (JwtException e) {
            log.warn("Token JWT inválido: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Erro inesperado ao extrair claims JWT: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Obtém a chave de assinatura derivada da SECRET_KEY.
     *
     * @return Chave criptográfica HMAC-SHA256
     */
    private Key getSignKey() {
        byte[] keyBytes = Decoders.BASE64.decode(SECRET_KEY);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    // =========================================================================
    // GERAÇÃO E VALIDAÇÃO DE TOKENS
    // =========================================================================

    /**
     * Gera um novo token JWT para o usuário informado.
     *
     * @param username Usuário autenticado
     * @return Token JWT assinado e válido
     */
    public String generateToken(String username) {
        Date agora = new Date(System.currentTimeMillis());
        Date expiracao = new Date(System.currentTimeMillis() + EXPIRATION_TIME);

        return Jwts.builder()
                .setSubject(username)
                .setIssuedAt(agora)
                .setExpiration(expiracao)
                .signWith(getSignKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Valida o token JWT comparando o username e verificando expiração.
     *
     * @param token    Token JWT
     * @param username Usuário esperado
     * @return true se o token for válido, false caso contrário
     */
    public boolean validateToken(String token, String username) {
        try {
            String extractedUsername = extractUsername(token);
            boolean valid = extractedUsername.equals(username) && !isTokenExpired(token);
            if (!valid) {
                log.warn("Token JWT inválido para usuário {}", username);
            }
            return valid;
        } catch (JwtException e) {
            log.warn("Falha na validação do token JWT: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Erro inesperado ao validar token JWT: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verifica se o token expirou.
     *
     * @param token Token JWT
     * @return true se expirado, false caso contrário
     */
    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }
}
