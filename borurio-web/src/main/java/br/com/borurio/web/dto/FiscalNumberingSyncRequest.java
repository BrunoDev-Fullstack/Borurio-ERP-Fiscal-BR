package br.com.borurio.web.dto;

import lombok.Data;

/**
 * Payload da OMS para sincronizar série e numeração — validado manualmente em
 * FiscalNumberingService (não @Valid) para garantir errorCode estável (SERIE_INVALIDA /
 * NUMERACAO_INVALIDA) em vez do envelope genérico de MethodArgumentNotValidException.
 */
@Data
public class FiscalNumberingSyncRequest {
    private String serie;
    private Integer proximoNumero;
}
