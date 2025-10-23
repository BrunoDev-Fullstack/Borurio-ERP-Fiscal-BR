package br.com.borurio.web.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR DE AUTENTICAÇÃO (AuthController)
 * =============================================================================
 * Responsável por expor o endpoint público de login:
 *    POST /auth/login
 *
 * Função:
 *   - Recebe credenciais (username, password).
 *   - Valida com AuthService.
 *   - Retorna token JWT válido para uso com Bearer Authorization.
 *
 * Padrões aplicados:
 *   - Retorno padronizado {code, message, token}
 *   - Tratamento centralizado de exceções
 *   - Logging estruturado sem exposição de credenciais
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Endpoint: POST /auth/login
     *
     * Exemplo de requisição:
     * <pre>
     * {
     *   "username": "admin",
     *   "password": "admin123"
     * }
     * </pre>
     *
     * Exemplo de resposta bem-sucedida:
     * <pre>
     * {
     *   "code": 200,
     *   "message": "Autenticação bem-sucedida",
     *   "token": "eyJhbGciOiJIUzI1NiIsInR5..."
     * }
     * </pre>
     *
     * @param request credenciais do usuário
     * @return token JWT válido ou mensagem de erro 401
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        log.info("Requisição de login recebida para usuário: {}", request.username());

        try {
            String token = authService.authenticate(request.username(), request.password());
            log.info("Usuário autenticado com sucesso: {}", request.username());

            return ResponseEntity.ok(
                    new AuthResponse(
                            HttpStatus.OK.value(),
                            "Autenticação bem-sucedida",
                            token
                    )
            );

        } catch (AuthenticationException e) {
            log.warn("Falha de autenticação para usuário {}: {}", request.username(), e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(
                            HttpStatus.UNAUTHORIZED.value(),
                            "Credenciais inválidas",
                            null
                    ));
        } catch (Exception e) {
            log.error("Erro inesperado durante autenticação: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(
                            HttpStatus.INTERNAL_SERVER_ERROR.value(),
                            "Erro interno de autenticação",
                            null
                    ));
        }
    }

    // =========================================================================
    // DTOs internos (records)
    // =========================================================================

    /**
     * Requisição de autenticação.
     */
    public record AuthRequest(String username, String password) {}

    /**
     * Resposta padronizada de autenticação.
     */
    public record AuthResponse(int code, String message, String token) {}
}
