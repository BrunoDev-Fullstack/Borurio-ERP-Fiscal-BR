package br.com.borurio.web.auth;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.mapper.DbUserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final DbUserMapper dbUserMapper;

    public UserDetailsServiceImpl(DbUserMapper dbUserMapper) {
        this.dbUserMapper = dbUserMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        DbUser user = dbUserMapper.findByEmail(username);
        if (user == null || Boolean.FALSE.equals(user.getAtivo())) {
            throw new UsernameNotFoundException("Usuário não encontrado ou inativo: " + username);
        }

        String role = user.getRole() != null ? user.getRole() : "OPERADOR";
        log.info("[AUTH] Login | user={} | role={}", username, role);

        return User.builder()
                .username(user.getEmail())
                .password(user.getSenha())
                .authorities(AuthorityUtils.createAuthorityList("ROLE_" + role))
                .build();
    }
}
