package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

public class NfeSequencia {

    private Long id;
    private String cnpjEmitente;
    private String serie;
    private int ultimoNumero;
    private LocalDateTime dataAtualizacao;
    /** Gate fiscal (V034) — id da nfe_emissao ativa nesta serie; null = gate livre. */
    private Long emissaoAtivaId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public void setCnpjEmitente(String cnpjEmitente) { this.cnpjEmitente = cnpjEmitente; }

    public String getSerie() { return serie; }
    public void setSerie(String serie) { this.serie = serie; }

    public int getUltimoNumero() { return ultimoNumero; }
    public void setUltimoNumero(int ultimoNumero) { this.ultimoNumero = ultimoNumero; }

    public LocalDateTime getDataAtualizacao() { return dataAtualizacao; }
    public void setDataAtualizacao(LocalDateTime dataAtualizacao) { this.dataAtualizacao = dataAtualizacao; }

    public Long getEmissaoAtivaId() { return emissaoAtivaId; }
    public void setEmissaoAtivaId(Long emissaoAtivaId) { this.emissaoAtivaId = emissaoAtivaId; }
}
