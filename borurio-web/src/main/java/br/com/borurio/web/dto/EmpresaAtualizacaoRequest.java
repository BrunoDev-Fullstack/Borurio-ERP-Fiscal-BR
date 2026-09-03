package br.com.borurio.web.dto;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Corpo de {@code PUT /api/app/empresas/{id}} — atualização PARCIAL explícita (Rota B, 13-08-2026).
 * Nunca reutiliza {@link br.com.borurio.app.entity.Empresa} como DTO HTTP: evita mass-assignment
 * (não existe campo {@code id}/{@code criadoEm}/{@code atualizadoEm} pra sobrescrever) e permite
 * distinguir os três estados que importam pra cada campo:
 *
 *   campo ausente do JSON      -> setter NUNCA é chamado -> {@link #presente(String)} = false
 *   campo presente, valor null -> setter é chamado com null -> presente = true, valor = null
 *   campo presente, com valor  -> setter é chamado com o valor -> presente = true, valor = X
 *
 * O rastreamento de presença usa o comportamento padrão do Jackson (nunca invoca setter de
 * propriedade ausente no JSON, sempre invoca para propriedade presente mesmo com valor null) —
 * cada setter grava seu próprio nome no conjunto {@link #presentes} ao ser chamado. Deliberadamente
 * SEM Lombok: {@code @Data} geraria setters sem esse efeito colateral, e um {@code toString()}
 * gerado arriscaria imprimir {@code certSenha} em log. Sem {@code @Valid}/Bean Validation aqui —
 * a semântica de obrigatório/nullable depende de PRESENÇA, não só de valor, então a validação
 * mora em {@link br.com.borurio.web.service.EmpresaAtualizacaoService}, não neste DTO.
 */
public class EmpresaAtualizacaoRequest {

    private final Set<String> presentes = new LinkedHashSet<>();

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
    private String indFinalPadrao;
    private Boolean ativo;
    private String certPath;
    private String certSenha;
    private String certTipo;
    private Boolean controleEstoqueAtivo;

    public boolean presente(String campo) {
        return presentes.contains(campo);
    }

    public String getCnpj() { return cnpj; }
    public void setCnpj(String cnpj) { this.cnpj = cnpj; presentes.add("cnpj"); }

    public String getRazaoSocial() { return razaoSocial; }
    public void setRazaoSocial(String razaoSocial) { this.razaoSocial = razaoSocial; presentes.add("razaoSocial"); }

    public String getNomeFantasia() { return nomeFantasia; }
    public void setNomeFantasia(String nomeFantasia) { this.nomeFantasia = nomeFantasia; presentes.add("nomeFantasia"); }

    public String getIe() { return ie; }
    public void setIe(String ie) { this.ie = ie; presentes.add("ie"); }

    public String getCrt() { return crt; }
    public void setCrt(String crt) { this.crt = crt; presentes.add("crt"); }

    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; presentes.add("uf"); }

    public String getLogradouro() { return logradouro; }
    public void setLogradouro(String logradouro) { this.logradouro = logradouro; presentes.add("logradouro"); }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; presentes.add("numero"); }

    public String getBairro() { return bairro; }
    public void setBairro(String bairro) { this.bairro = bairro; presentes.add("bairro"); }

    public String getMunicipio() { return municipio; }
    public void setMunicipio(String municipio) { this.municipio = municipio; presentes.add("municipio"); }

    public String getCodigoMunicipio() { return codigoMunicipio; }
    public void setCodigoMunicipio(String codigoMunicipio) { this.codigoMunicipio = codigoMunicipio; presentes.add("codigoMunicipio"); }

    public String getCep() { return cep; }
    public void setCep(String cep) { this.cep = cep; presentes.add("cep"); }

    public String getSerieNfePadrao() { return serieNfePadrao; }
    public void setSerieNfePadrao(String serieNfePadrao) { this.serieNfePadrao = serieNfePadrao; presentes.add("serieNfePadrao"); }

    public String getIndFinalPadrao() { return indFinalPadrao; }
    public void setIndFinalPadrao(String indFinalPadrao) { this.indFinalPadrao = indFinalPadrao; presentes.add("indFinalPadrao"); }

    public Boolean getAtivo() { return ativo; }
    public void setAtivo(Boolean ativo) { this.ativo = ativo; presentes.add("ativo"); }

    public String getCertPath() { return certPath; }
    public void setCertPath(String certPath) { this.certPath = certPath; presentes.add("certPath"); }

    /** Nunca logar/expor este campo — ver {@link br.com.borurio.web.service.EmpresaAtualizacaoService}. */
    public String getCertSenha() { return certSenha; }
    public void setCertSenha(String certSenha) { this.certSenha = certSenha; presentes.add("certSenha"); }

    public String getCertTipo() { return certTipo; }
    public void setCertTipo(String certTipo) { this.certTipo = certTipo; presentes.add("certTipo"); }

    public Boolean getControleEstoqueAtivo() { return controleEstoqueAtivo; }
    public void setControleEstoqueAtivo(Boolean controleEstoqueAtivo) { this.controleEstoqueAtivo = controleEstoqueAtivo; presentes.add("controleEstoqueAtivo"); }

    /** Nunca imprime certSenha — só os nomes dos campos presentes, para log/depuração seguros. */
    @Override
    public String toString() {
        return "EmpresaAtualizacaoRequest{presentes=" + presentes + "}";
    }
}
