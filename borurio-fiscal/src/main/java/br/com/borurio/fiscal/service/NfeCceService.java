package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeCceEventoPreparado;
import br.com.borurio.fiscal.dto.NfeCceRequest;

/**
 * Transporte de CC-e (evento 110110) junto à SEFAZ. A partir do Gate CC-e (12-08-2026), a
 * DERIVAÇÃO da sequência e a decisão de retry-vs-nova-tentativa deixam de ser responsabilidade
 * desta classe — quem chama já sabe exatamente qual nSeqEvento usar (reservado atomicamente via
 * nfe_evento_sequencia, nunca mais COUNT(*) sobre NfeLog). {@link #corrigir}/overloads antigos
 * foram removidos: nenhum caminho de produção pode mais transmitir 110110 sem passar pelo gate
 * (o endpoint cru legado foi desabilitado — ver NfeCceController).
 */
public interface NfeCceService {

    /** Monta e assina o XML do evento -- nenhuma chamada de rede. */
    NfeCceEventoPreparado prepararEvento(NfeCceRequest req, String cnpjEmitente, String uf,
                                          CertificadoContexto certContexto, int nSeqEvento) throws Exception;

    /**
     * Transmite um evento já preparado. Qualquer exceção aqui é reclassificada como
     * {@link br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException} -- mesmo princípio
     * já usado em NfeCancelamentoServiceImpl.transmitirEvento: a fronteira local/rede é comprovada
     * pela FASE (esta chamada), nunca por tipo de exceção.
     */
    String transmitirEvento(NfeCceEventoPreparado preparado, CertificadoContexto certContexto) throws Exception;
}
