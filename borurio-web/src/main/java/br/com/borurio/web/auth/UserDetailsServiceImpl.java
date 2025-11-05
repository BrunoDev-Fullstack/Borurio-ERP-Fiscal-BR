package br.com.borurio.web.auth;

import br.com.borurio.web.model.UserAccount;
import br.com.borurio.web.repository.UserAccountRepository;
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
 * Responsável por carregar os dados de autenticação de usuários.
 *
 * Perfis:
 *  - dev → fornece um usuário mock ("admin" / "admin123") para testes locais.
 *  - hom/prd → autenticação real via banco (tabela user_account).
 *
 * Padrão técnico:
 *   - Spring Security 6 / Java 17
 *   - PasswordEncoder: BCrypt
 *   - Entidade: UserAccount
 *   - Campos esperados: username, password, role, enabled
 *
 * Projeto: ERP Fiscal Borurio BR
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Última revisão: 04/11/2025
 * =============================================================================
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final PasswordEncoder passwordEncoder;
    private final UserAccountRepository userRepository;
    private final String activeProfile;

    @Autowired
    public UserDetailsServiceImpl(
            PasswordEncoder passwordEncoder,
            UserAccountRepository userRepository,
            Environment environment
    ) {
        this.passwordEncoder = passwordEncoder;
        this.userRepository = userRepository;
        this.activeProfile = environment.getActiveProfiles().length > 0
                ? environment.getActiveProfiles()[0]
                : "default";
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        System.out.println("===========================================================");
        System.out.println("[AUTH] Iniciando autenticação para usuário: " + username);
        System.out.println("[AUTH] Perfil ativo: " + activeProfile);
        System.out.println("===========================================================");

        // ============================================================
        // 1. MODO MOCK (DESENVOLVIMENTO)
        // ============================================================
        if ("dev".equalsIgnoreCase(activeProfile)) {
            if (!"admin".equalsIgnoreCase(username)) {
                throw new UsernameNotFoundException("Usuário não encontrado no modo DEV: " + username);
            }

            System.out.println("[AUTH] Modo DEV — usuário mock carregado: admin");

            return User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .roles("ADMIN")
                    .build();
        }

        // ============================================================
        // 2. MODO REAL (HOMOLOGAÇÃO / PRODUÇÃO)
        // ============================================================
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado: " + username));

        // Adaptação para campos atuais (enabled / ativo)
        boolean ativo;
        try {
            ativo = user.isEnabled(); // campo atual padrão
        } catch (Exception e) {
            // fallback caso a entidade tenha outro nome
            try {
                ativo = (boolean) user.getClass().getMethod("getAtivo").invoke(user);
            } catch (Exception ex) {
                ativo = true; // fallback de segurança
            }
        }

        if (!ativo) {
            throw new UsernameNotFoundException("Usuário inativo: " + username);
        }

        System.out.println("[AUTH] Usuário autenticado via banco: " + username);

        return new User(
                user.getUsername(),
                user.getPassword(),
                ativo,
                true,
                true,
                true,
                AuthorityUtils.createAuthorityList(user.getRole())
        );
    }
}
