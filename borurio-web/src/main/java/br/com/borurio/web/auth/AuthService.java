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

    public String authenticate(String username, String password) throws AuthenticationException {

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );

            return jwtUtil.generateToken(username);

        } catch (BadCredentialsException e) {
            log.warn("Falha de autenticação");
            throw e;

        } catch (AuthenticationException e) {
            log.warn("Erro de autenticação");
            throw e;
        }
    }
}
