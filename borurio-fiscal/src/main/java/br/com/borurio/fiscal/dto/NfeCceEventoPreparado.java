package br.com.borurio.fiscal.dto;

/**
 * XML de um evento de CC-e (110110) ja montado e assinado, ainda NAO transmitido -- mesmo papel
 * de NfeEventoPreparado (cancelamento), com nSeqEvento explicito (CC-e tem identidade crescente
 * legitima, nunca fixa em 1). Separa a fase local (sem rede) da fase de transmissao (rede, nunca
 * dentro de transacao).
 */
public record NfeCceEventoPreparado(
        String idEvento,
        String dhEvento,
        String xmlAssinado,
        String payloadHash,
        String chave,
        String cnpj,
        int nSeqEvento
) {}
