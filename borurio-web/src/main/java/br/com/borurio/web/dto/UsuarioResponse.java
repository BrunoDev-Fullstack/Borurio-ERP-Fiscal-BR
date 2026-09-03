package br.com.borurio.web.dto;

import br.com.borurio.app.entity.DbUser;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UsuarioResponse {

    private Long id;
    private Long empresaId;
    private String nome;
    private String email;
    private String role;
    private Boolean ativo;
    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;

    public static UsuarioResponse from(DbUser u) {
        UsuarioResponse r = new UsuarioResponse();
        r.setId(u.getId());
        r.setEmpresaId(u.getEmpresaId());
        r.setNome(u.getNome());
        r.setEmail(u.getEmail());
        r.setRole(u.getRole());
        r.setAtivo(u.getAtivo());
        r.setDataCriacao(u.getDataCriacao());
        r.setDataAtualizacao(u.getDataAtualizacao());
        return r;
    }
}
