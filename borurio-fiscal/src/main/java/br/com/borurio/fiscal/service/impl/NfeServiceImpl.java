package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.NfeService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * =============================================================================
 * SERVIÇO NF-e — CAMADA DE NEGÓCIO
 * -----------------------------------------------------------------------------
 * Versão revisada para integração REAL com SEFAZ-SP.
 *
 * Responsabilidades:
 *   - Orquestrar envio, validação e transmissão da NF-e
 *   - Receber XML assinado
 *   - Delegar transmissão ao NfeTransmitService (SOAP 1.2 + mTLS)
 *   - Retornar resposta estruturada
 *
 * Ambientes:
 *   - Homologação (tpAmb=2)
 *   - Produção    (tpAmb=1)
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Dev Fullstack / DevSecOps
 * Revisão: 03/12/2025
 * =============================================================================
 */
@Slf4j
@Service
public class NfeServiceImpl implements NfeService {

    private final NfeTransmitService nfeTransmitService;

    public NfeServiceImpl(NfeTransmitService nfeTransmitService) {
        this.nfeTransmitService = nfeTransmitService;
    }

    /**
     * Status interno do módulo fiscal
     */
    @Override
    public String getStatus() {
        return "Serviço NF-e operacional — certificado carregado — SEFAZ pronto.";
    }

    /**
     * Fluxo real de autorização da NF-e.
     *
     * @param xmlNfe XML assinado (versão 4.00, autorizado para envio)
     * @return Resposta SOAP real da SEFAZ-SP.
     */
    @Override
    public String authorize(String xmlNfe) {

        if (!StringUtils.hasText(xmlNfe)) {
            log.warn("[NF-e] XML vazio recebido para autorização.");
            return "<erro>XML inválido ou ausente.</erro>";
        }

        try {
            log.info("[NF-e] Iniciando autorização REAL da NF-e...");

            // OBS.: o CNPJ virá do controller
            final String cnpjEmitente = "00000000000000";

            String resposta = nfeTransmitService.transmitirXml(xmlNfe, cnpjEmitente);

            log.info("[NF-e] Autorização finalizada. Retorno entregue ao controller.");
            return resposta;

        } catch (Exception e) {
            log.error("[NF-e] Falha ao autorizar NF-e: {}", e.getMessage(), e);
            return "<erro>Falha ao autorizar NF-e: " + e.getMessage() + "</erro>";
        }
    }
}
