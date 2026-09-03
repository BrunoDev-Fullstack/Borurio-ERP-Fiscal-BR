package br.com.borurio.web.dto;

/** Como abrirCiclo() resolveu a chamada — decide se PedidoEmissaoService reserva estoque de novo. */
public enum TipoAberturaCiclo {
    /** Gate livre, nenhum ciclo anterior do pedido — número novo alocado. */
    NOVA_ABERTURA,
    /** Retomada de um ciclo em RESERVADO (crash antes de transmitir) — mesma linha, mesmo número, estoque intocado. */
    RETOMADA_RESERVADO,
    /** Retomada de um ciclo em AGUARDANDO_CORRECAO (retry pós-rejeição) — mesma linha, mesmo número, estoque reservado de novo. */
    RETOMADA_AGUARDANDO_CORRECAO
}
