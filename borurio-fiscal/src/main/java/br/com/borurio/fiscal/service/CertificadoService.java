package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;

/**
 * Interface responsável por definir o contrato de serviço
 * para manipulação e disponibilização do certificado digital A1 (.pfx).
 *
 * Este serviço é utilizado pelos módulos fiscais para estabelecer
 * comunicação segura (HTTPS) com os web services da SEFAZ.
 *
 * Padrões aplicados:
 * - Interface segregada (ISP - SOLID)
 * - Injeção de dependência via Spring Boot
 * - Suporte a múltiplos ambientes (dev / prd)
 *
 * @author Borurio
 * @since Sprint Fiscal 2.2
 */
public interface CertificadoService {

    /**
     * Retorna o contexto SSL inicializado com o certificado digital A1 (.pfx).
     *
     * @return SSLContext configurado com o certificado A1.
     */
    SSLContext getSslContext();
}
