package br.com.borurio.fiscal.service;

/**
 * Serviço responsável pela consulta de status da SEFAZ (NF-e).
 *
 * Observações:
 * - Atualmente retorna XML bruto (SOAP Response)
 * - Futuramente deve evoluir para objeto tipado (DTO)
 * - Método preparado para expansão (UF / ambiente)
 */
public interface NfeStatusService {

    /**
     * Consulta o status do serviço da SEFAZ.
     *
     * @return XML de resposta da SEFAZ
     */
    String consultarStatusServico();

}