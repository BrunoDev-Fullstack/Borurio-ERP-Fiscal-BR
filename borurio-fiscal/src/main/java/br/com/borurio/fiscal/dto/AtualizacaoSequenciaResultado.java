package br.com.borurio.fiscal.dto;

import java.time.LocalDateTime;

/**
 * Resultado de uma atualização de sequência via {@code NfeSequenciaService.atualizarSequencia()}.
 * {@code aplicado=false} indica chamada idempotente (o valor já era exatamente esse).
 * {@code proximoNumeroAnterior=null} indica que a sequência não existia antes desta chamada —
 * {@code 0} não é usado como sentinela porque é um valor de nNF tecnicamente inválido e pode ser
 * confundido com um estado fiscal real.
 */
public record AtualizacaoSequenciaResultado(
        String cnpjEmitente,
        String serie,
        Integer proximoNumeroAnterior,
        int proximoNumeroAtual,
        boolean aplicado,
        LocalDateTime atualizadoEm
) {}
