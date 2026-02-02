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
 * Endpoint público:
 *   POST /auth/login
 *
 * Retorna token JWT válido para autenticação Bearer.
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

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {

        if (request == null || request.username() == null || request.password() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new AuthResponse(
                            HttpStatus.BAD_REQUEST.value(),
                            "Credenciais inválidas",
                            null
                    ));
        }

        try {
            String token = authService.authenticate(request.username(), request.password());

            log.info("Autenticação realizada com sucesso");

            return ResponseEntity.ok(
                    new AuthResponse(
                            HttpStatus.OK.value(),
                            "Autenticação bem-sucedida",
                            token
                    )
            );

        } catch (AuthenticationException e) {
            log.warn("Falha de autenticação");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthResponse(
                            HttpStatus.UNAUTHORIZED.value(),
                            "Credenciais inválidas",
                            null
                    ));
        } catch (Exception e) {
            log.error("Erro interno durante autenticação", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new AuthResponse(
                            HttpStatus.INTERNAL_SERVER_ERROR.value(),
                            "Erro interno de autenticação",
                            null
                    ));
        }
    }

    public record AuthRequest(String username, String password) {}
    public record AuthResponse(int code, String message, String token) {}
}
