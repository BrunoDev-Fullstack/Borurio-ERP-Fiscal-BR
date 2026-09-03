package br.com.borurio.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class OmsFiscalAuthorizationRequest {

    @NotBlank(message = "codigoEmpresaOms é obrigatório")
    @Size(max = 100, message = "codigoEmpresaOms deve ter no máximo 100 caracteres")
    private String codigoEmpresaOms;

    @NotBlank(message = "cnpj é obrigatório")
    @Pattern(regexp = "\\d{14}", message = "cnpj deve conter exatamente 14 dígitos")
    private String cnpj;

    @NotBlank(message = "certBase64 é obrigatório")
    private String certBase64;

    @NotBlank(message = "certSenha é obrigatória")
    private String certSenha;

    public String getCodigoEmpresaOms() { return codigoEmpresaOms; }
    public void setCodigoEmpresaOms(String codigoEmpresaOms) { this.codigoEmpresaOms = codigoEmpresaOms; }

    public String getCnpj() { return cnpj; }
    public void setCnpj(String cnpj) { this.cnpj = cnpj; }

    public String getCertBase64() { return certBase64; }
    public void setCertBase64(String certBase64) { this.certBase64 = certBase64; }

    public String getCertSenha() { return certSenha; }
    public void setCertSenha(String certSenha) { this.certSenha = certSenha; }
}
