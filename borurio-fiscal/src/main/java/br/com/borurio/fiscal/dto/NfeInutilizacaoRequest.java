package br.com.borurio.fiscal.dto;

public class NfeInutilizacaoRequest {

    private String ano;
    private String serie;
    private String nNFIni;
    private String nNFFin;
    private String justificativa;

    public String getAno() { return ano; }
    public void setAno(String ano) { this.ano = ano; }

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public String getNNFIni() { return nNFIni; }
    public void setNNFIni(String nNFIni) { this.nNFIni = nNFIni; }

    public String getNNFFin() { return nNFFin; }
    public void setNNFFin(String nNFFin) { this.nNFFin = nNFFin; }

    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }
}
