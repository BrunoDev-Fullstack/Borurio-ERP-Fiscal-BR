package br.com.borurio.fiscal.dto;

import java.util.List;

public class NfeEmissaoRequest {

    // Numeração
    private String serie;
    private String numero;
    private String naturezaOperacao;

    // Destinatário
    private String destCnpjCpf;
    private String destRazaoSocial;
    private String destIe;
    private String destLogradouro;
    private String destNumero;
    private String destComplemento;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destUf;
    private String destCep;

    // Itens
    private List<NfeEmissaoItem> itens;

    // Informações adicionais
    private String informacoesAdicionais;

    public NfeEmissaoRequest() {}

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }

    public String getNaturezaOperacao() { return naturezaOperacao; }
    public void setNaturezaOperacao(String naturezaOperacao) { this.naturezaOperacao = naturezaOperacao; }

    public String getDestCnpjCpf() { return destCnpjCpf; }
    public void setDestCnpjCpf(String destCnpjCpf) { this.destCnpjCpf = destCnpjCpf; }

    public String getDestRazaoSocial() { return destRazaoSocial; }
    public void setDestRazaoSocial(String destRazaoSocial) { this.destRazaoSocial = destRazaoSocial; }

    public String getDestIe() { return destIe; }
    public void setDestIe(String destIe) { this.destIe = destIe; }

    public String getDestLogradouro() { return destLogradouro; }
    public void setDestLogradouro(String destLogradouro) { this.destLogradouro = destLogradouro; }

    public String getDestNumero() { return destNumero; }
    public void setDestNumero(String destNumero) { this.destNumero = destNumero; }

    public String getDestComplemento() { return destComplemento; }
    public void setDestComplemento(String destComplemento) { this.destComplemento = destComplemento; }

    public String getDestBairro() { return destBairro; }
    public void setDestBairro(String destBairro) { this.destBairro = destBairro; }

    public String getDestCodigoMunicipio() { return destCodigoMunicipio; }
    public void setDestCodigoMunicipio(String destCodigoMunicipio) { this.destCodigoMunicipio = destCodigoMunicipio; }

    public String getDestMunicipio() { return destMunicipio; }
    public void setDestMunicipio(String destMunicipio) { this.destMunicipio = destMunicipio; }

    public String getDestUf() { return destUf; }
    public void setDestUf(String destUf) { this.destUf = destUf; }

    public String getDestCep() { return destCep; }
    public void setDestCep(String destCep) { this.destCep = destCep; }

    public List<NfeEmissaoItem> getItens() { return itens; }
    public void setItens(List<NfeEmissaoItem> itens) { this.itens = itens; }

    public String getInformacoesAdicionais() { return informacoesAdicionais; }
    public void setInformacoesAdicionais(String informacoesAdicionais) { this.informacoesAdicionais = informacoesAdicionais; }
}
