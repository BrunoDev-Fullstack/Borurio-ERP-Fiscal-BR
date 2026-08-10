package br.com.borurio.fiscal.exception;

/**
 * Uma sincronização de série/numeração (vinda da OMS, ou qualquer outro chamador) tentou mutar
 * {@code nfe_sequencia} (avançar o número, ou trocar a série apontada por
 * {@code Empresa.serieNfePadrao}) enquanto existe uma emissão fiscal ativa (Gate 1 —
 * {@code emissao_ativa_id} não nulo) para o CNPJ/série envolvidos.
 *
 * A mensagem desta exceção (uso interno/log) carrega o id da emissão ativa para diagnóstico —
 * quem traduz isso para o cliente HTTP (ex.: FiscalNumberingService) NÃO deve repassar esse id
 * nem o pedidoId no payload de resposta.
 */
public class SequenciaComEmissaoAtivaException extends RuntimeException {

    private final String cnpjEmitente;
    private final String serie;
    private final Long emissaoAtivaId;

    public SequenciaComEmissaoAtivaException(String cnpjEmitente, String serie, Long emissaoAtivaId) {
        super("Sequência de CNPJ=" + cnpjEmitente + " série=" + serie
                + " tem emissão ativa (nfe_emissao id=" + emissaoAtivaId
                + ") — sincronização bloqueada até o ciclo ter destino definitivo.");
        this.cnpjEmitente = cnpjEmitente;
        this.serie = serie;
        this.emissaoAtivaId = emissaoAtivaId;
    }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public String getSerie() { return serie; }
    public Long getEmissaoAtivaId() { return emissaoAtivaId; }
}
