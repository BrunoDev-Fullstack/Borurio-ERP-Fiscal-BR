package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;

/**
 * =============================================================================
 * Serviço: CertificadoServiceImpl (Modo Desenvolvimento)
 * -----------------------------------------------------------------------------
 * Função:
 *   Implementação simplificada do serviço de certificado digital A1 (.pfx),
 *   utilizada exclusivamente no ambiente de desenvolvimento (mock SEFAZ-SP).
 *
 *   Esta versão não realiza o carregamento real do arquivo PFX,
 *   permitindo que o módulo fiscal opere sem dependência de certificados
 *   durante os testes e simulações de NF-e.
 *
 * Contexto:
 *   - Sprint Fiscal 2.7 – Integração SEFAZ-SP / NF-e 4.00
 *   - Perfil ativo: dev
 *
 * Boas práticas DevSecOps:
 *   - Não logar informações sensíveis
 *   - Isolar dependências de produção (SSL, keystore)
 *   - Permitir comutação fácil para CertificadoServiceImpl PRD
 * =============================================================================
 * Autor: Bruno Ribeiro
 * Data: 16/10/2025
 */
@Service
@Profile({"dev", "mock", "!test"})
public class CertificadoServiceImpl implements CertificadoService {

    private static final Logger logger = LoggerFactory.getLogger(CertificadoServiceImpl.class);

    /**
     * Retorna um SSLContext nulo para o modo desenvolvimento.
     * Esse comportamento evita inicializações desnecessárias e falhas de
     * dependência quando o certificado A1 ainda não está disponível.
     *
     * @return SSLContext (nulo no modo mock)
     */
    @Override
    public SSLContext getSslContext() {
        logger.info("[CertificadoServiceImpl] Ambiente de desenvolvimento ativo — certificado A1 não carregado (modo mock SEFAZ-SP).");
        return null;
    }

    /**
     * Exibe um resumo seguro do estado do serviço de certificado.
     * Este método pode ser expandido futuramente para diagnósticos.
     *
     * @return String informando o status do certificado
     */
    public String getStatus() {
        return "CertificadoServiceImpl (DEV): modo mock ativo — SSL desativado";
    }
}
