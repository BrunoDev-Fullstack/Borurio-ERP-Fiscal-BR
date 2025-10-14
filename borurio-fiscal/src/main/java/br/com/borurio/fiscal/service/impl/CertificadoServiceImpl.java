package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.CertificadoService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;

/**
 * Implementação simplificada do serviço de certificado digital A1 (.pfx)
 * utilizada exclusivamente no perfil de desenvolvimento (mock SEFAZ-SP).
 *
 * Esta versão não realiza o carregamento real do arquivo PFX,
 * permitindo a execução do sistema sem dependência de certificado válido.
 *
 * Autor: Bruno Ribeiro
 * Sprint Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
 */
@Service
@Profile("!test") // ativo em todos os perfis exceto teste automatizado
public class CertificadoServiceImpl implements CertificadoService {

    /**
     * Implementação simplificada que não inicializa o SSLContext.
     * Retorna nulo para indicar que a comunicação segura está desativada
     * durante o desenvolvimento e uso do mock SEFAZ.
     */
    @Override
    public SSLContext getSslContext() {
        System.out.println("[CertificadoServiceImpl] Modo de desenvolvimento ativo – certificado A1 não carregado.");
        return null;
    }
}
