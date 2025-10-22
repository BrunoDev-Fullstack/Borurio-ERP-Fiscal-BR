package br.com.borurio.web.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR DE AUTENTICAÇÃO
 * -----------------------------------------------------------------------------
 * Endpoint público: /auth/login
 * Retorna token JWT para autenticação Bearer.
 * =============================================================================
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        try {
            String token = authService.authenticate(request.username(), request.password());
            return ResponseEntity.ok(new AuthResponse("Autenticação bem-sucedida", token));
        } catch (AuthenticationException e) {
            return ResponseEntity.status(401).body(new AuthResponse("Credenciais inválidas", null));
        }
    }

    public record AuthRequest(String username, String password) {}
    public record AuthResponse(String message, String token) {}
}
