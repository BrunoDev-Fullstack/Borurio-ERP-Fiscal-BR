package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquestra a Consulta Situação NF-e (consSitNFe) para a reconciliação (Gate 3, 10-08-2026):
 * chama o transporte real ({@link NfeTransmitService#consultarNfe}) e interpreta o resultado com
 * {@link NfeConsultaSituacaoParser}, devolvendo sempre um {@link NfeConsultaSituacaoRetorno}
 * estruturado — nunca a string SOAP crua.
 *
 * Três desfechos possíveis, nunca confundidos entre si:
 *   - falha de transporte (timeout/conexão) → {@link SefazTransmissaoIncertaException} propagada,
 *     nunca convertida em resultado — o chamador decide o que fazer com uma tentativa que nem
 *     chegou a ter resposta.
 *   - resposta SOAP recebida mas ilegível → {@link NfeConsultaSituacaoRetorno#isFalhaParse()}.
 *   - resposta SOAP recebida e interpretada (mesmo cStat=217/635) → retorno estruturado normal.
 *
 * Deliberadamente sem transação: I/O de rede nunca deve ocorrer dentro de uma transação de banco
 * (mesmo princípio já documentado em NfeEmissaoService) — quem chama este serviço decide a
 * fronteira transacional da aplicação do resultado, não este método.
 */
@Service
public class NfeConsultaSituacaoService {

    private static final Logger log = LoggerFactory.getLogger(NfeConsultaSituacaoService.class);

    private final NfeTransmitService transmitService;
    private final NfeConsultaSituacaoParser parser;

    public NfeConsultaSituacaoService(NfeTransmitService transmitService, NfeConsultaSituacaoParser parser) {
        this.transmitService = transmitService;
        this.parser = parser;
    }

    public NfeConsultaSituacaoRetorno consultar(String chaveNfe, String uf, int ambiente) {
        String resposta = transmitService.consultarNfe(chaveNfe, uf, ambiente);
        NfeConsultaSituacaoRetorno retorno = parser.parse(resposta);
        log.info("[NfeConsultaSituacao] Consulta interpretada | chave={} | cStat={} | protNFePresente={} | falhaParse={}",
                chaveNfe, retorno.getCStat(), retorno.isProtNFePresente(), retorno.isFalhaParse());
        return retorno;
    }
}
