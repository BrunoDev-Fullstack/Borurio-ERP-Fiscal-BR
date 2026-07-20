package br.com.borurio.web.dto;

import java.time.LocalDateTime;

/**
 * serieAnterior/serieAtual referem-se à série padrão da EMPRESA (Empresa.serieNfePadrao).
 * proximoNumeroAnterior/proximoNumeroAtual referem-se sempre à sequência da SÉRIE DE DESTINO
 * (serieAtual) — nunca uma mistura entre a numeração da série antiga e da série nova.
 * proximoNumeroAnterior=null indica que a sequência de destino não existia antes desta chamada.
 */
public record FiscalNumberingSyncResponse(
        String cnpjEmitente,
        String serieAnterior,
        String serieAtual,
        Integer proximoNumeroAnterior,
        int proximoNumeroAtual,
        boolean aplicado,
        LocalDateTime atualizadoEm
) {}
