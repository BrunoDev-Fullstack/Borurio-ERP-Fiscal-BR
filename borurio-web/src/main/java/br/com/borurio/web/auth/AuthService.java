package br.com.borurio.web.auth;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.mapper.DbUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final DbUserMapper dbUserMapper;

    public AuthService(AuthenticationManager authenticationManager,
                       JwtUtil jwtUtil,
                       DbUserMapper dbUserMapper) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.dbUserMapper = dbUserMapper;
    }

    public String authenticate(String username, String password) throws AuthenticationException {

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, password)
            );

            DbUser user = dbUserMapper.findByEmail(username);
            Long empresaId = (user != null) ? user.getEmpresaId() : null;

            return jwtUtil.generateToken(username, empresaId);

        } catch (BadCredentialsException e) {
            log.warn("Falha de autenticação");
            throw e;

        } catch (AuthenticationException e) {
            log.warn("Erro de autenticação");
            throw e;
        }
    }
}
