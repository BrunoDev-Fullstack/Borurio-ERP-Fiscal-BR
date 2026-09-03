package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeInutilizacaoRequest;

public interface NfeInutilizacaoService {

    /** Inutiliza usando o emitente global (EmitenteProperties) — comportamento legado single-CNPJ. */
    String inutilizar(NfeInutilizacaoRequest req) throws Exception;

    /**
     * Inutiliza usando o contexto fiscal explícito da empresa selecionada — multi-CNPJ.
     * Se cnpjEmitente/uf/certContexto forem null, cai no comportamento legado.
     */
    String inutilizar(NfeInutilizacaoRequest req, String cnpjEmitente, String uf,
                       CertificadoContexto certContexto) throws Exception;
}
