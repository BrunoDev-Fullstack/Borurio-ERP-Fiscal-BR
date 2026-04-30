package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeInutilizacaoRequest;

public interface NfeInutilizacaoService {

    String inutilizar(NfeInutilizacaoRequest req) throws Exception;
}
