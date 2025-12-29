package br.com.borurio.fiscal.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * =============================================================================
 * PROPRIEDADES — CERTIFICADO FISCAL (A1)
 * =============================================================================
 * Mapeia as configurações do certificado digital utilizado na integração
 * fiscal com a SEFAZ (NF-e 4.00).
 *
 * Prefixo YAML:
 *   fiscal.certificate
 *
 * Exemplo:
 *   fiscal:
 *     certificate:
 *       enabled: true
 *       type: PKCS12
 *       pfx-path: /app/certs/keystore/jcho-keystore.p12
 *       pfx-password: senha
 *       truststore-path: /app/certs/truststore/sefaz-truststore.jks
 *       truststore-password: changeit
 *
 * Observações:
 * - DEV/HOM: enabled=false permite mock SEFAZ
 * - PRD: enabled=true é obrigatório (fail-fast)
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * =============================================================================
 */
@Validated
@Component
@ConfigurationProperties(prefix = "fiscal.certificate")
public class FiscalCertificateProperties {

    /**
     * Habilita ou desabilita o uso do certificado digital.
     */
    private boolean enabled = true;

    /**
     * Tipo do certificado (ex: PKCS12 / A1).
     */
    @NotBlank
    private String type;

    /**
     * Caminho absoluto do arquivo PFX / P12.
     */
    @NotBlank
    private String pfxPath;

    /**
     * Senha do certificado PFX / P12.
     */
    @NotBlank
    private String pfxPassword;

    /**
     * Caminho absoluto do truststore SEFAZ (JKS).
     */
    @NotBlank
    private String truststorePath;

    /**
     * Senha do truststore SEFAZ.
     */
    @NotBlank
    private String truststorePassword;

    /* ===================== GETTERS / SETTERS ===================== */

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getPfxPath() {
        return pfxPath;
    }

    public void setPfxPath(String pfxPath) {
        this.pfxPath = pfxPath;
    }

    public String getPfxPassword() {
        return pfxPassword;
    }

    public void setPfxPassword(String pfxPassword) {
        this.pfxPassword = pfxPassword;
    }

    public String getTruststorePath() {
        return truststorePath;
    }

    public void setTruststorePath(String truststorePath) {
        this.truststorePath = truststorePath;
    }

    public String getTruststorePassword() {
        return truststorePassword;
    }

    public void setTruststorePassword(String truststorePassword) {
        this.truststorePassword = truststorePassword;
    }
}
