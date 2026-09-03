package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Conjunto de credenciais de um certificado A1 já carregado em memória.
 * Passado por parâmetro para os overloads por-empresa de
 * AssinaturaXmlService e NfeTransmitServiceImpl.
 */
public record CertificadoContexto(
        Long empresaId,
        PrivateKey privateKey,
        X509Certificate certificate,
        SSLContext sslContext
) {}
