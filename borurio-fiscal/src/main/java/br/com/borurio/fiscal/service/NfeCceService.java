package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeCceRequest;

public interface NfeCceService {

    /**
     * Emite CC-e usando o emitente global (EmitenteProperties). Mantido para o endpoint
     * interno cru (/api/fiscal/nfe/cce), sem contexto de pedido.
     *
     * @param req DTO com chaveNfe, correcao e sequencia opcional
     * @return XML de resposta SEFAZ (retEnvEvento)
     */
    String corrigir(NfeCceRequest req) throws Exception;

    /**
     * Emite CC-e usando o contexto fiscal explícito da empresa emitente — multi-CNPJ.
     * Se cnpjEmitente/uf/certContexto forem null, cai no comportamento legado.
     *
     * @param req DTO com chaveNfe, correcao e sequencia opcional
     * @param cnpjEmitente CNPJ da empresa que emitiu a NF-e (só dígitos); null → usa o global
     * @param uf UF da empresa emitente; null → usa o global
     * @param certContexto certificado da empresa emitente; null → usa o certificado global
     * @return XML de resposta SEFAZ (retEnvEvento)
     */
    String corrigir(NfeCceRequest req, String cnpjEmitente, String uf,
                     CertificadoContexto certContexto) throws Exception;
}
