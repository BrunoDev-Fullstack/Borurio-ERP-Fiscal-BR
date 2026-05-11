package br.com.borurio.app.entity;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class Empresa {

    private Long id;
    private String cnpj;
    private String razaoSocial;
    private String nomeFantasia;
    private String ie;
    private String crt;
    private String uf;
    private String logradouro;
    private String numero;
    private String bairro;
    private String municipio;
    private String codigoMunicipio;
    private String cep;
    private String serieNfePadrao;
    private Boolean ativo;
    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;

    // Fase 8B: certificado A1 por empresa (opcional — fallback para cert global)
    private String certPath;
    private String certSenha;
    private String certTipo;
}
