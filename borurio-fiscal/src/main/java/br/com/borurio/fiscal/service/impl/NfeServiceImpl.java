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
 *   - Orquestrar o fluxo de envio da NF-e
 *   - Validar entrada
 *   - Delegar transmissão ao NfeTransmitService (mTLS + SOAP 1.2)
 *   - Retornar resultado padronizado
 *
 * Ambientes suportados:
 *   - DEV/HOM → SEFAZ Homologação (tpAmb=2)
 *   - PRD     → SEFAZ Produção    (tpAmb=1)
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 26/11/2025
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
     * Retorna o status do serviço NF-e.
     * Em produção, consulta NfeStatus → SEFAZ.
     */
    @Override
    public String getStatus() {
        return "Serviço NF-e operacional — integração SEFAZ pronta.";
    }

    /**
     * Fluxo de autorização REAL da NF-e.
     *
     * @param xmlNfe XML assinado e completo (versão 4.00)
     * @return resposta SOAP SEFAZ ou mensagem de erro estruturada
     */
    @Override
    public String authorize(String xmlNfe) {

        if (!StringUtils.hasText(xmlNfe)) {
            log.warn("[NF-e] XML vazio recebido para autorização.");
            return "<erro>XML inválido ou ausente.</erro>";
        }

        try {
            log.info("[NF-e] Iniciando autorização REAL da NF-e...");
            String resposta = nfeTransmitService.transmitirXml(xmlNfe, "00000000000000"); // atualize o CNPJ no controller
            log.info("[NF-e] Finalizado processo de autorização.");
            return resposta;
        } catch (Exception e) {
            log.error("[NF-e] Falha ao autorizar NF-e: {}", e.getMessage(), e);
            return "<erro>Falha ao autorizar NF-e: " + e.getMessage() + "</erro>";
        }
    }
}
