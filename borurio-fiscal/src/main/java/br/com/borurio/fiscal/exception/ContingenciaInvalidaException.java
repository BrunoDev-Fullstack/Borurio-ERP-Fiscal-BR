package br.com.borurio.fiscal.exception;

/**
 * Fase 1 SVC (17-08-2026) — uma tentativa de abrir contingência (Caminho B,
 * {@code NfeContingenciaService.abrirContingencia}) foi recusada: emissão não é o ciclo ativo da
 * série, já foi substituída antes (double-substitute), está em {@code RESERVADO} (Caminho A, não
 * implementado nesta fase), ou está em estado terminal/{@code AGUARDANDO_CORRECAO} (ciclo já
 * resolvido, contingência não se aplica).
 */
public class ContingenciaInvalidaException extends RuntimeException {

    public ContingenciaInvalidaException(String message) {
        super(message);
    }

    public ContingenciaInvalidaException(String message, Throwable cause) {
        super(message, cause);
    }
}
