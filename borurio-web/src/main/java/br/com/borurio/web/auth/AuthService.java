package br.com.borurio.web.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * =============================================================================
 * SERVIÇO DE AUTENTICAÇÃO (AuthService)
 * =============================================================================
 * Responsável por validar as credenciais de login e gerar tokens JWT seguros.
 *
 * Fluxo principal:
 *   1. Recebe usuário e senha do AuthController.
 *   2. Autentica via AuthenticationManager (Spring Security).
 *   3. Em caso de sucesso, gera token JWT com JwtUtil.
 *
 * Boas práticas aplicadas:
 *   - Tratamento explícito de falhas de autenticação.
 *   - Logging estruturado sem expor credenciais.
 *   - Separação clara entre autenticação e emissão de token.
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
 * =============================================================================
 */
@Slf4j
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;

    public AuthService(AuthenticationManager authenticationManager, JwtUtil jwtUtil) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
    }

    /**
     * Autentica o usuário e gera um token JWT em caso de sucesso.
     *
     * @param username Nome de usuário informado no login
     * @param password Senha informada
     * @return Token JWT válido
     * @throws AuthenticationException se as credenciais forem inválidas
     */
    public String authenticate(String username, String password) throws AuthenticationException {
        try {
            log.info("Tentativa de autenticação para o usuário: {}", username);

            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );

            String token = jwtUtil.generateToken(username);
            log.info("Autenticação bem-sucedida para usuário: {}", username);
            return token;

        } catch (BadCredentialsException e) {
            log.warn("Falha de autenticação — credenciais inválidas para usuário: {}", username);
            throw e;

        } catch (AuthenticationException e) {
            log.error("Erro de autenticação para usuário {}: {}", username, e.getMessage());
            throw e;

        } catch (Exception e) {
            log.error("Erro inesperado durante autenticação de {}: {}", username, e.getMessage(), e);
            throw new BadCredentialsException("Erro interno de autenticação.");
        }
    }
}
