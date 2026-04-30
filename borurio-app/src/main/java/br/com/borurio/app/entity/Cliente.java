package br.com.borurio.app.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Cliente {

    private Long id;

    // =========================================================================
    // IDENTIFICAÇÃO FISCAL
    // =========================================================================

    /** "PJ" para Pessoa Jurídica, "PF" para Pessoa Física. */
    private String tipoPessoa;

    /** 14 dígitos, sem pontuação. Obrigatório quando tipoPessoa=PJ. */
    private String cnpj;

    /** 11 dígitos, sem pontuação. Obrigatório quando tipoPessoa=PF. */
    private String cpf;

    /** Razão social (PJ) ou nome completo (PF). Mapeado para <xNome> na NF-e. */
    private String razaoSocial;

    /** Nome fantasia (PJ). Opcional. */
    private String nomeFantasia;

    /** Inscrição Estadual. Obrigatório para PJ contribuinte do ICMS. */
    private String inscricaoEstadual;

    // =========================================================================
    // CONTATO (mantido para compatibilidade com dados legados)
    // =========================================================================

    private String nome;
    private String email;
    private String telefone;

    // =========================================================================
    // ENDEREÇO — obrigatório para emissão de NF-e
    // =========================================================================

    private String logradouro;
    private String numero;
    private String complemento;
    private String bairro;

    /** Código IBGE do município (7 dígitos). Mapeado para <cMun> na NF-e. */
    private String codigoMunicipio;

    private String municipio;

    /** Sigla do estado (ex: "SP"). */
    private String uf;

    /** 8 dígitos, sem hífen. */
    private String cep;

    // =========================================================================
    // CONTROLE
    // =========================================================================

    /** 1 = ativo, 0 = inativo. */
    private Integer estado;

    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
}
