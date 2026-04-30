package br.com.borurio.fiscal.dto;

public class NfeCceRequest {

    /** Chave de acesso da NF-e a ser corrigida (44 dígitos). */
    private String chaveNfe;

    /** Texto da correção (15–1000 caracteres). */
    private String correcao;

    /**
     * Sequência do evento (1–20). Quando nulo, o sistema deriva do banco.
     * Use apenas para reenvio explícito de uma sequência já conhecida.
     */
    private Integer sequencia;

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public String getCorrecao() { return correcao; }
    public void setCorrecao(String correcao) { this.correcao = correcao; }

    public Integer getSequencia() { return sequencia; }
    public void setSequencia(Integer sequencia) { this.sequencia = sequencia; }
}
