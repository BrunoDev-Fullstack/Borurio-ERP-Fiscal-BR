package br.com.borurio.app.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Cliente {

    private Long id;
    private Long empresaId;

    @NotBlank(message = "tipoPessoa é obrigatório: 'PJ' ou 'PF'")
    @Pattern(regexp = "PJ|PF", message = "tipoPessoa deve ser 'PJ' ou 'PF'")
    private String tipoPessoa;

    private String cnpj;
    private String cpf;

    @NotBlank(message = "Razão social / nome completo é obrigatório")
    private String razaoSocial;

    private String nomeFantasia;
    private String inscricaoEstadual;

    // Contato legado
    private String nome;
    private String email;
    private String telefone;

    // Endereço
    private String logradouro;
    private String numero;
    private String complemento;
    private String bairro;
    private String codigoMunicipio;
    private String municipio;
    private String uf;
    private String cep;

    private Integer estado;
    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
}
