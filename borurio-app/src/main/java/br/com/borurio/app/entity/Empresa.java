package br.com.borurio.app.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Empresa {

    private Long id;

    @NotBlank(message = "CNPJ é obrigatório")
    @Size(min = 14, max = 14, message = "CNPJ deve conter 14 dígitos")
    private String cnpj;

    @NotBlank(message = "Razão social é obrigatória")
    @Size(max = 60, message = "Razão social deve ter no máximo 60 caracteres")
    private String razaoSocial;

    private String nomeFantasia;
    private String ie;

    @NotBlank(message = "CRT é obrigatório")
    private String crt;

    @NotBlank(message = "UF é obrigatória")
    @Size(min = 2, max = 2, message = "UF deve ter 2 caracteres")
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

    // Fase 8B: certificado A1 por empresa (opcional)
    private String certPath;
    private String certSenha;
    private String certTipo;
}
