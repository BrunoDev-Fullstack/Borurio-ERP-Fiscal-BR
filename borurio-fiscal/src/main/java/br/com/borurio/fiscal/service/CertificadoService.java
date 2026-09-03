package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

/**
 * Serviço responsável por fornecer acesso ao certificado digital A1 (PKCS12)
 * utilizado na assinatura XML (NF-e) e na comunicação TLS com a SEFAZ.
 *
 * A interface expõe apenas os elementos base (KeyStore, alias e senha),
 * e deriva automaticamente a chave privada e o certificado através de
 * métodos default.
 *
 * Essa abordagem evita duplicação de lógica nas implementações e mantém
 * compatibilidade com diferentes formas de armazenamento do certificado.
 */
public interface CertificadoService {

    /**
     * SSLContext configurado com o certificado.
     * Usado na comunicação HTTPS com a SEFAZ.
     */
    SSLContext getSslContext();

    /**
     * KeyStore carregado do arquivo PKCS12 (.pfx).
     */
    KeyStore getKeyStore();

    /**
     * Alias da entrada de chave dentro do KeyStore.
     *
     * IMPORTANTE:
     * O alias não deve ser fixo. Deve ser resolvido dinamicamente,
     * pois varia conforme a autoridade certificadora.
     */
    String getAlias();

    /**
     * Senha da chave privada.
     *
     * Para certificados A1, geralmente é a mesma senha do KeyStore.
     */
    char[] getSenha();

    /**
     * Status do certificado (controle interno).
     */
    String getStatus();

    // ---------------------------------------------------------------------
    // MÉTODOS DERIVADOS (default)
    // ---------------------------------------------------------------------

    /**
     * Retorna a chave privada utilizada na assinatura XMLDSIG.
     *
     * A chave é obtida diretamente do KeyStore com base no alias e senha.
     */
    default PrivateKey getPrivateKey() {
        try {
            KeyStore ks = getKeyStore();
            String alias = getAlias();
            char[] senha = getSenha();

            if (ks == null) {
                throw new IllegalStateException("KeyStore não inicializado");
            }

            if (alias == null || alias.isBlank()) {
                throw new IllegalStateException("Alias do certificado não definido");
            }

            var key = ks.getKey(alias, senha);

            if (key == null) {
                throw new IllegalStateException(
                        "Chave privada não encontrada para o alias: " + alias);
            }

            if (!(key instanceof PrivateKey)) {
                throw new IllegalStateException(
                        "Entrada não é uma chave privada: " + key.getClass().getName());
            }

            return (PrivateKey) key;

        } catch (Exception e) {
            throw new IllegalStateException("Erro ao obter PrivateKey", e);
        }
    }

    /**
     * Retorna o certificado X509 utilizado na assinatura.
     *
     * Esse certificado será incluído no bloco <KeyInfo> da assinatura XML.
     */
    default X509Certificate getCertificate() {
        try {
            KeyStore ks = getKeyStore();
            String alias = getAlias();

            if (ks == null) {
                throw new IllegalStateException("KeyStore não inicializado");
            }

            if (alias == null || alias.isBlank()) {
                throw new IllegalStateException("Alias do certificado não definido");
            }

            var cert = ks.getCertificate(alias);

            if (cert == null) {
                throw new IllegalStateException(
                        "Certificado não encontrado para o alias: " + alias);
            }

            if (!(cert instanceof X509Certificate)) {
                throw new IllegalStateException(
                        "Certificado inválido: " + cert.getClass().getName());
            }

            return (X509Certificate) cert;

        } catch (Exception e) {
            throw new IllegalStateException("Erro ao obter certificado X509", e);
        }
    }
}