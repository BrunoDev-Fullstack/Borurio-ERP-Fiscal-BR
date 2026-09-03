package br.com.borurio.web.dto;

import br.com.borurio.fiscal.entity.NfeEmissao;

/** Resultado de NfeEmissaoService.abrirCiclo(). */
public record AberturaCicloResultado(NfeEmissao emissao, TipoAberturaCiclo tipo) {}
