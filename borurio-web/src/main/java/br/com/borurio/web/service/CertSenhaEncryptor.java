package br.com.borurio.web.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criptografa/decriptografa cert_senha em repouso com AES-256-GCM.
 *
 * Formato armazenado no banco: ENC(<base64(iv + ciphertext)>)
 * Se o valor não começar com "ENC(" é tratado como texto claro (migração graceful).
 *
 * A chave é lida de CERT_ENCRYPTION_KEY (Base64, 32 bytes = 256 bits).
 * Se a variável não estiver configurada o serviço opera em modo passthrough
 * (sem criptografia) com aviso de log — adequado para desenvolvimento local.
 */
@Component
public class CertSenhaEncryptor {

    private static final Logger log = LoggerFactory.getLogger(CertSenhaEncryptor.class);
    private static final String PREFIX = "ENC(";
    private static final String SUFFIX = ")";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${cert.encryption.key:}")
    private String encryptionKeyBase64;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            log.warn("[CertEncryptor] CERT_ENCRYPTION_KEY nao configurada — modo passthrough (sem criptografia)");
            return;
        }
        try {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "CERT_ENCRYPTION_KEY deve ter exatamente 32 bytes (256 bits) em Base64");
            }
            secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("[CertEncryptor] Chave AES-256-GCM carregada com sucesso");
        } catch (Exception e) {
            log.error("[CertEncryptor] Falha ao carregar chave de criptografia", e);
            throw new IllegalStateException("CERT_ENCRYPTION_KEY invalida", e);
        }
    }

    /**
     * Criptografa o valor e retorna no formato ENC(...).
     * Retorna o valor original se a chave não estiver configurada.
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return plaintext;
        if (secretKey == null) return plaintext;
        if (isEncrypted(plaintext)) return plaintext;

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined) + SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar cert_senha", e);
        }
    }

    /**
     * Decriptografa valor no formato ENC(...).
     * Retorna o valor original se não estiver criptografado (migração graceful).
     */
    public String decrypt(String value) {
        if (value == null || value.isBlank()) return value;
        if (!isEncrypted(value)) return value;
        if (secretKey == null) {
            log.warn("[CertEncryptor] Valor ENC(...) encontrado mas chave nao configurada — retornando como esta");
            return value;
        }

        try {
            String inner = value.substring(PREFIX.length(), value.length() - SUFFIX.length());
            byte[] combined = Base64.getDecoder().decode(inner);

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[CertEncryptor] Falha ao decriptografar — usando valor como texto claro");
            return value;
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX) && value.endsWith(SUFFIX);
    }

    /**
     * Criptografa bytes brutos (ex: PKCS12) com AES-256-GCM.
     * Formato armazenado: IV(12 bytes) || CIPHERTEXT+TAG.
     *
     * Fail-closed: lança IllegalStateException se a chave não estiver configurada.
     * Certificados A1 jamais devem ser armazenados sem criptografia autenticada.
     */
    public byte[] encryptBytes(byte[] plainBytes) {
        if (plainBytes == null) return null;
        if (secretKey == null) {
            throw new IllegalStateException(
                    "CERT_ENCRYPTION_KEY não configurada — certificados OMS não podem ser " +
                    "armazenados sem criptografia autenticada. Configure a variável de ambiente.");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plainBytes);

            byte[] result = new byte[GCM_IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.length);
            return result;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao criptografar bytes do certificado", e);
        }
    }

    /**
     * Decriptografa bytes previamente criptografados por {@link #encryptBytes}.
     *
     * Fail-closed: lança IllegalStateException se a chave não estiver configurada
     * ou se o conteúdo for insuficiente para conter IV + tag GCM.
     */
    public byte[] decryptBytes(byte[] encryptedBytes) {
        if (encryptedBytes == null) return null;
        if (secretKey == null) {
            throw new IllegalStateException(
                    "CERT_ENCRYPTION_KEY não configurada — impossível decriptografar certificado OMS.");
        }
        // Mínimo: IV (12) + tag GCM (16) = 28 bytes; menos que isso é conteúdo corrompido
        int minLen = GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);
        if (encryptedBytes.length < minLen) {
            throw new IllegalStateException(
                    "Conteúdo cifrado OMS inválido: esperado mínimo " + minLen +
                    " bytes, recebido " + encryptedBytes.length);
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[encryptedBytes.length - GCM_IV_LENGTH];
            System.arraycopy(encryptedBytes, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return cipher.doFinal(ciphertext);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao decriptografar bytes do certificado — conteúdo corrompido ou chave inválida", e);
        }
    }
}
