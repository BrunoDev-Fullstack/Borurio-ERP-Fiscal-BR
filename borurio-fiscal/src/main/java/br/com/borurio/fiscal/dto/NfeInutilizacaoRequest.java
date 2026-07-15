package br.com.borurio.fiscal.dto;

public class NfeInutilizacaoRequest {

    private String ano;
    private String serie;
    private String nNFIni;
    private String nNFFin;
    private String justificativa;

    /**
     * CNPJ da empresa emitente (só dígitos), opcional — multi-CNPJ. Se informado,
     * a inutilização usa o certificado e a UF dessa empresa em vez do emitente global.
     * Endpoint interno/ADMIN — não faz parte do contrato OMS.
     */
    private String cnpjEmitente;

    public String getAno() { return ano; }
    public void setAno(String ano) { this.ano = ano; }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public void setCnpjEmitente(String cnpjEmitente) { this.cnpjEmitente = cnpjEmitente; }

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public String getNNFIni() { return nNFIni; }
    public void setNNFIni(String nNFIni) { this.nNFIni = nNFIni; }

    public String getNNFFin() { return nNFFin; }
    public void setNNFFin(String nNFFin) { this.nNFFin = nNFFin; }

    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }
}
