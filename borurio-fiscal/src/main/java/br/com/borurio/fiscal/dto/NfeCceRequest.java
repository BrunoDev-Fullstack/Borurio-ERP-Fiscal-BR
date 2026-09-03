package br.com.borurio.fiscal.dto;

public class NfeCceRequest {

    /** Chave de acesso da NF-e a ser corrigida (44 dígitos). */
    private String chaveNfe;

    /**
     * Texto da correção (15–1000 caracteres) -- a partir da segunda CC-e, deve ser o texto FINAL
     * CUMULATIVO (Ajuste SINIEF 7/05, cláusula 14-A, §4º: a última CC-e substitui as anteriores e
     * precisa consolidar tudo que ainda vale). O Borurio nunca concatena ou reescreve -- persiste
     * e transmite exatamente o que for enviado aqui.
     */
    private String correcao;

    // Campo "sequencia" REMOVIDO (Gate CC-e, 12-08-2026): a sequência agora é reservada
    // atomicamente por nfe_evento_sequencia, nunca informada pelo chamador -- era o principal
    // vetor de desalinhamento entre o contador local e o que a SEFAZ realmente tem registrado.

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public String getCorrecao() { return correcao; }
    public void setCorrecao(String correcao) { this.correcao = correcao; }
}
