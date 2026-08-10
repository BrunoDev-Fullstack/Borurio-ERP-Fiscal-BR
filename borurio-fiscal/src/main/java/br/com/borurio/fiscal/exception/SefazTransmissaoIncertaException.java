package br.com.borurio.fiscal.exception;

/**
 * Marca que uma tentativa real de I/O de rede com a SEFAZ foi iniciada e seu desfecho é
 * desconhecido — lançada exclusivamente por {@code NfeOrquestradorService.processar()}, ao redor
 * da ÚNICA chamada que efetivamente toca a rede ({@code NfeTransmitService.transmitirXml}).
 *
 * A fronteira local/transmissão deixa de ser inferida por tipo de exceção (frágil — qualquer
 * exceção nova de uma etapa local seria classificada incorretamente por omissão) e passa a ser
 * comprovada pela FASE em que ocorreu: se esta exceção (ou algo que a envolva na cadeia de
 * causas) não estiver presente, a falha é estruturalmente impossível de ter alcançado a rede —
 * aconteceu em conversão de XML, validação XSD, assinatura digital ou qualquer persistência local
 * anterior à chamada de transmissão.
 */
public class SefazTransmissaoIncertaException extends RuntimeException {

    public SefazTransmissaoIncertaException(Throwable cause) {
        super("Falha durante a transmissão à SEFAZ — resultado desconhecido: "
                + (cause != null ? cause.getMessage() : null), cause);
    }
}
