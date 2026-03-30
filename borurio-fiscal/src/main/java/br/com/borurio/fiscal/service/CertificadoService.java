package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;
import java.security.KeyStore;

/**
 * =============================================================================
 * INTERFACE: CertificadoService
 * =============================================================================
 * Responsável por gerenciar o certificado digital A1 (PKCS12) utilizado na
 * comunicação segura com a SEFAZ (NF-e 4.00).
 *
 * Funcionalidades:
 *  - Carregamento do certificado (.pfx)
 *  - Inicialização do SSLContext (TLS 1.2+)
 *  - Exposição do KeyStore para assinatura XML (XMLDSig)
 *  - Exposição de alias e senha para acesso à chave privada
 *
 * Ambientes:
 *  - dev / hom: pode operar com certificado mock
 *  - prd: obrigatório certificado válido (ICP-Brasil)
 *
 * =============================================================================
 */
public interface CertificadoService {

    /**
     * Retorna o SSLContext configurado com o certificado A1.
     */
    SSLContext getSslContext();

    /**
     * Retorna o KeyStore carregado do certificado A1.
     */
    KeyStore getKeyStore();

    /**
     * Retorna o alias da chave dentro do KeyStore.
     *
     * Necessário para:
     *  - recuperar chave privada
     *  - assinatura XML
     */
    String getAlias();

    /**
     * Retorna a senha da chave privada do certificado.
     *
     * Necessário para:
     *  - acesso à PrivateKey
     */
    char[] getSenha();

    /**
     * Retorna o status atual do certificado.
     */
    String getStatus();
}