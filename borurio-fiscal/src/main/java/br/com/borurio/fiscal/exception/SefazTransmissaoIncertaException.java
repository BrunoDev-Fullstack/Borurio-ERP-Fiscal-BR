package br.com.borurio.fiscal.exception;

/**
 * Marca que uma tentativa real de I/O de rede com a SEFAZ foi iniciada e seu desfecho é
 * desconhecido. Lançada em dois pontos, ambos ao redor da chamada que efetivamente toca a rede,
 * nunca da lógica de interpretação da resposta:
 *   - {@code NfeOrquestradorService.processar()}, ao redor de {@code NfeTransmitService.transmitirXml}
 *     (Gate 1/P1) — transmissão de uma NF-e nova.
 *   - {@code NfeTransmitServiceImpl.consultarNfe()} (Gate 3) — Consulta Situação usada na
 *     reconciliação. Uma resposta SEFAZ com cStat conhecido (mesmo 217/635) NUNCA lança esta
 *     exceção — só falha real de transporte (timeout, conexão recusada, host desconhecido) antes
 *     de qualquer XML de resposta existir.
 *
 * A fronteira local/rede deixa de ser inferida por tipo de exceção (frágil — qualquer exceção
 * nova de uma etapa local seria classificada incorretamente por omissão) e passa a ser comprovada
 * pela FASE em que ocorreu: se esta exceção (ou algo que a envolva na cadeia de causas) não
 * estiver presente, a falha é estruturalmente impossível de ter alcançado a rede.
 */
public class SefazTransmissaoIncertaException extends RuntimeException {

    public SefazTransmissaoIncertaException(Throwable cause) {
        super("Falha durante a transmissão à SEFAZ — resultado desconhecido: "
                + (cause != null ? cause.getMessage() : null), cause);
    }
}
