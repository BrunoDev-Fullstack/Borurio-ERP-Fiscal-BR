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
 * USER DETAILS SERVICE
 * -----------------------------------------------------------------------------
 * Serviço responsável por carregar os dados de autenticação do usuário.
 *
 * - Modo DEV: fornece um usuário mock ("admin" / "123456") para testes locais.
 * - Modo HOM/PRD: realiza a autenticação real consultando a tabela user_account.
 *
 * Padrão técnico:
 *   - Spring Security 6 / Java 17
 *   - PasswordEncoder: BCrypt
 *   - Repositório JPA: UserAccountRepository
 *
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
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

    /**
     * Carrega o usuário pelo nome de login.
     *
     * @param username Nome de usuário informado no login
     * @return Detalhes do usuário autenticado
     * @throws UsernameNotFoundException caso o usuário não exista ou esteja inativo
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        // ============================================================
        // 1. MODO MOCK (DESENVOLVIMENTO)
        // ============================================================
        if ("dev".equalsIgnoreCase(activeProfile)) {
            if (!"admin".equalsIgnoreCase(username)) {
                throw new UsernameNotFoundException("Usuário não encontrado no modo DEV: " + username);
            }

            // Senha fixa '123456' para ambiente de desenvolvimento
            String encodedPassword = passwordEncoder.encode("123456");

            return User.builder()
                    .username("admin")
                    .password(encodedPassword)
                    .roles("ADMIN")
                    .build();
        }

        // ============================================================
        // 2. MODO REAL (HOMOLOGAÇÃO / PRODUÇÃO)
        // ============================================================
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado: " + username));

        if (!user.isAtivo()) {
            throw new UsernameNotFoundException("Usuário inativo: " + username);
        }

        return new User(
                user.getUsername(),
                user.getPassword(),
                user.isAtivo(),
                true,
                true,
                true,
                AuthorityUtils.createAuthorityList(user.getRole())
        );
    }
}
