package br.com.borurio.web.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR DE AUTENTICAÇÃO
 * =============================================================================
 * Responsável por gerenciar o processo de login do sistema.
 *
 * Endpoint público: POST /auth/login
 * Retorna: token JWT válido para autenticação do tipo Bearer.
 * -----------------------------------------------------------------------------
 * Fluxo:
 *   1. Recebe credenciais (username e password).
 *   2. Autentica o usuário via AuthService.
 *   3. Retorna token JWT no corpo da resposta.
 *
 * Política de segurança:
 *   - Rota liberada em {@link br.com.borurio.web.config.SecurityConfig}
 *   - Protegida pelo {@link br.com.borurio.web.auth.JwtFilter} nas demais rotas.
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@RestController
@RequestMapping("/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Endpoint de login.
     * Exemplo de requisição:
     * POST /auth/login
     * {
     *   "username": "admin",
     *   "password": "123456"
     * }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        log.info("Tentativa de login para usuário: {}", request.username());
        try {
            String token = authService.authenticate(request.username(), request.password());
            log.info("Login bem-sucedido para usuário: {}", request.username());
            return ResponseEntity.ok(new AuthResponse("Autenticação bem-sucedida", token));
        } catch (AuthenticationException e) {
            log.warn("Falha de autenticação para usuário: {}", request.username());
            return ResponseEntity.status(401).body(new AuthResponse("Credenciais inválidas", null));
        } catch (Exception e) {
            log.error("Erro interno ao processar login: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(new AuthResponse("Erro interno no servidor", null));
        }
    }

    /** DTO de requisição de autenticação. */
    public record AuthRequest(String username, String password) {}

    /** DTO de resposta de autenticação. */
    public record AuthResponse(String message, String token) {}
}
