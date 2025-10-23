package br.com.borurio.web.auth;

import br.com.borurio.web.model.UserAccount;
import br.com.borurio.web.repository.UserAccountRepository;
import lombok.extern.slf4j.Slf4j;
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
 * USER DETAILS SERVICE — SERVIÇO DE AUTENTICAÇÃO DE USUÁRIO
 * =============================================================================
 * Responsável por carregar as credenciais e permissões do usuário.
 *
 * Modo de operação:
 *   - DEV  → retorna usuário mock "admin"/"admin123" para testes locais.
 *   - HOM/PRD → autentica usuário real da tabela user_account.
 *
 * Padrões e tecnologias:
 *   - Spring Security 6 (UserDetailsService)
 *   - PasswordEncoder: BCrypt
 *   - Repositório: JPA (UserAccountRepository)
 *
 * Boas práticas:
 *   - Evita exposição de senhas em logs.
 *   - Falhas tratadas com exceções específicas.
 *   - Compatível com múltiplos perfis (dev, hom, prd).
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 23/10/2025
 * =============================================================================
 */
@Slf4j
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
     * Carrega o usuário pelo nome de login, conforme o perfil ativo.
     *
     * @param username Nome de usuário informado no login
     * @return Instância de {@link UserDetails} representando o usuário autenticado
     * @throws UsernameNotFoundException se o usuário não existir ou estiver inativo
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {

        // ============================================================
        // 1. MODO MOCK (DESENVOLVIMENTO)
        // ============================================================
        if ("dev".equalsIgnoreCase(activeProfile)) {
            log.debug("Autenticação em modo DEV — usuário mock ativo.");

            if (!"admin".equalsIgnoreCase(username)) {
                log.warn("Usuário inválido em modo DEV: {}", username);
                throw new UsernameNotFoundException("Usuário não encontrado no modo DEV: " + username);
            }

            return User.builder()
                    .username("admin")
                    .password(passwordEncoder.encode("admin123"))
                    .roles("ADMIN")
                    .build();
        }

        // ============================================================
        // 2. MODO REAL (HOMOLOGAÇÃO / PRODUÇÃO)
        // ============================================================
        log.debug("Autenticação real — perfil ativo: {}", activeProfile);

        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado: {}", username);
                    return new UsernameNotFoundException("Usuário não encontrado: " + username);
                });

        if (!user.isAtivo()) {
            log.warn("Usuário inativo: {}", username);
            throw new UsernameNotFoundException("Usuário inativo: " + username);
        }

        log.info("Usuário autenticado com sucesso: {} [perfil: {}]", username, activeProfile);

        return new User(
                user.getUsername(),
                user.getPassword(),
                user.isAtivo(),
                true,   // conta não expirada
                true,   // credenciais não expiradas
                true,   // conta não bloqueada
                AuthorityUtils.createAuthorityList(user.getRole())
        );
    }
}
