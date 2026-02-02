package br.com.borurio.web.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * =============================================================================
 * USER DETAILS SERVICE — BORURIO ERP FISCAL BR
 *
 * Estratégia:
 * - DEV: usuário técnico fixo (admin/admin123)
 * - HOM: usuário técnico fixo (admin/admin123) — TEMPORÁRIO
 * - PRD: somente autenticação externa (DB / IAM / Keycloak / etc.)
 * =============================================================================
 */
@Slf4j
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final PasswordEncoder passwordEncoder;
    private final String activeProfile;

    public UserDetailsServiceImpl(
            PasswordEncoder passwordEncoder,
            Environment environment
    ) {
        this.passwordEncoder = passwordEncoder;
        this.activeProfile = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles()[0]
                : "default";
    }

    @Override
    public UserDetails loadUserByUsername(String username)
            throws UsernameNotFoundException {

        log.info("[AUTH] Processando autenticação (profile={})", activeProfile);

        // DEV e HOM — acesso técnico controlado
        if ("dev".equalsIgnoreCase(activeProfile) || "hom".equalsIgnoreCase(activeProfile)) {

            if (!"admin".equalsIgnoreCase(username)) {
                throw new UsernameNotFoundException("Usuário não encontrado");
            }

            return User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_ADMIN"))
                    .build();
        }

        // PRD — bloquear login técnico
        throw new UsernameNotFoundException(
                "Autenticação técnica desabilitada em produção"
        );
    }
}
