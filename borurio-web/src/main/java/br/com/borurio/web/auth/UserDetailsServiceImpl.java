package br.com.borurio.web.auth;

import org.springframework.beans.factory.annotation.Autowired;
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
 * -----------------------------------------------------------------------------
 * Serviço de autenticação do módulo WEB.
 *
 * RESPONSABILIDADE DO WEB:
 *  - DEV: autenticação MOCK (admin/admin123)
 *  - HOM/PRD: ponto de extensão para integração externa (App/Auth Service)
 *
 * IMPORTANTE:
 *  - NÃO utiliza JPA
 *  - NÃO acessa banco
 *  - NÃO conhece entidades de persistência
 *
 * Projeto: ERP Fiscal Borurio BR
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * =============================================================================
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final PasswordEncoder passwordEncoder;
    private final String activeProfile;

    @Autowired
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
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        System.out.println("===========================================================");
        System.out.println("[AUTH] Iniciando autenticação");
        System.out.println("[AUTH] Usuário: " + username);
        System.out.println("[AUTH] Perfil ativo: " + activeProfile);
        System.out.println("===========================================================");

        // ============================================================
        // MODO DEV — USUÁRIO MOCK
        // ============================================================
        if ("dev".equalsIgnoreCase(activeProfile)) {

            if (!"admin".equalsIgnoreCase(username)) {
                throw new UsernameNotFoundException(
                        "Usuário não encontrado no modo DEV: " + username
                );
            }

            System.out.println("[AUTH] Modo DEV — usuário mock autenticado");

            return User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_ADMIN"))
                    .accountExpired(false)
                    .accountLocked(false)
                    .credentialsExpired(false)
                    .disabled(false)
                    .build();
        }

        // ============================================================
        // HOM / PRD — PONTO DE EXTENSÃO
        // ============================================================
        throw new UsernameNotFoundException(
                "Autenticação real ainda não configurada para o perfil: " + activeProfile
        );
    }
}

