package br.com.borurio.fiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Dados cadastrais do emitente (empresa) usados na geração de NF-e.
 * Lidos de fiscal.emitente.* no application.yml de cada ambiente.
 */
@Configuration
@ConfigurationProperties(prefix = "fiscal.emitente")
public class EmitenteProperties {

    private String cnpj;
    private String razaoSocial;
    private String nomeFantasia;
    private String ie;
    /** Código de Regime Tributário: 1=Simples Nacional, 3=Regime Normal. */
    private String crt = "1";
    private String logradouro;
    private String numero;
    private String bairro;
    private String municipio;
    /** Código IBGE do município (7 dígitos). Ex: 3550308 = São Paulo. */
    private String codigoMunicipio;
    /** Sigla da UF. Ex: SP. */
    private String uf;
    /** CEP sem hífen (8 dígitos). */
    private String cep;

    public String getCnpj() { return cnpj; }
    public void setCnpj(String cnpj) { this.cnpj = cnpj; }

    public String getRazaoSocial() { return razaoSocial; }
    public void setRazaoSocial(String razaoSocial) { this.razaoSocial = razaoSocial; }

    public String getNomeFantasia() { return nomeFantasia; }
    public void setNomeFantasia(String nomeFantasia) { this.nomeFantasia = nomeFantasia; }

    public String getIe() { return ie; }
    public void setIe(String ie) { this.ie = ie; }

    public String getCrt() { return crt; }
    public void setCrt(String crt) { this.crt = crt; }

    public String getLogradouro() { return logradouro; }
    public void setLogradouro(String logradouro) { this.logradouro = logradouro; }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }

    public String getBairro() { return bairro; }
    public void setBairro(String bairro) { this.bairro = bairro; }

    public String getMunicipio() { return municipio; }
    public void setMunicipio(String municipio) { this.municipio = municipio; }

    public String getCodigoMunicipio() { return codigoMunicipio; }
    public void setCodigoMunicipio(String codigoMunicipio) { this.codigoMunicipio = codigoMunicipio; }

    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; }

    public String getCep() { return cep; }
    public void setCep(String cep) { this.cep = cep; }
}
