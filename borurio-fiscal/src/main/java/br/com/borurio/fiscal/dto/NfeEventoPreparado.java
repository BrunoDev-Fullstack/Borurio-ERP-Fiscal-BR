package br.com.borurio.fiscal.dto;

/**
 * XML de um evento ja montado e assinado, ainda NAO transmitido -- separa a fase local (sem
 * rede, pode acontecer dentro ou fora de transacao de banco) da fase de transmissao (rede, nunca
 * dentro de transacao). Usado pelo gate de cancelamento (NfeEventoService, 12-08-2026) para
 * persistir dhEvento/payloadHash no claim ANTES de tocar a rede -- nunca reconstroi um evento
 * "semanticamente igual" silenciosamente apos uma incerteza de transporte.
 */
public record NfeEventoPreparado(
        String idEvento,
        String dhEvento,
        String xmlAssinado,
        String payloadHash,
        String chave,
        String cnpj
) {}
