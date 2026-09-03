package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.fiscal.service.CertificadoContexto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Carrega e cacheia certificados A1 por empresa.
 * Retorna Optional.empty() se a empresa não tiver cert_path configurado —
 * o chamador deve fazer fallback para o certificado global.
 */
@Service
public class EmpresaCertificadoService {

    private static final Logger log = LoggerFactory.getLogger(EmpresaCertificadoService.class);

    private final ConcurrentHashMap<Long, CertificadoContexto> cache = new ConcurrentHashMap<>();
    private final CertSenhaEncryptor encryptor;

    public EmpresaCertificadoService(CertSenhaEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    public Optional<CertificadoContexto> resolverPorEmpresa(Empresa empresa) {
        if (empresa == null || empresa.getId() == null
                || empresa.getCertPath() == null || empresa.getCertPath().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(
                cache.computeIfAbsent(empresa.getId(), id -> carregarContexto(empresa))
        );
    }

    /** Remove o certificado do cache (use quando a empresa atualizar seu cert). */
    public void invalidar(Long empresaId) {
        cache.remove(empresaId);
        log.info("[EmpresaCert] Cache invalidado | empresaId={}", empresaId);
    }

    private CertificadoContexto carregarContexto(Empresa empresa) {
        try {
            String path  = empresa.getCertPath();
            String senha = encryptor.decrypt(
                    empresa.getCertSenha() != null ? empresa.getCertSenha() : "");
            String tipo  = empresa.getCertTipo()  != null ? empresa.getCertTipo()  : "PKCS12";

            log.info("[EmpresaCert] Carregando certificado | empresaId={} | path={} | tipo={}",
                    empresa.getId(), path, tipo);

            try (InputStream is = abrirArquivo(path)) {
                KeyStore ks = KeyStore.getInstance(tipo);
                ks.load(is, senha.toCharArray());

                String alias   = resolverAlias(ks, path);
                PrivateKey pk  = (PrivateKey) ks.getKey(alias, senha.toCharArray());
                X509Certificate cert = (X509Certificate) ks.getCertificate(alias);

                KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                        KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, senha.toCharArray());

                SSLContext ssl = SSLContext.getInstance("TLSv1.2");
                ssl.init(kmf.getKeyManagers(), null, null);

                log.info("[EmpresaCert] Certificado OK | empresaId={} | alias={}", empresa.getId(), alias);
                return new CertificadoContexto(empresa.getId(), pk, cert, ssl);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao carregar certificado da empresa id=" + empresa.getId(), e);
        }
    }

    private InputStream abrirArquivo(String path) throws Exception {
        ClassPathResource cp = new ClassPathResource(path);
        if (cp.exists()) return cp.getInputStream();

        File f = new File(path);
        if (f.exists()) return new FileInputStream(f);

        throw new IllegalStateException("Certificado não encontrado: " + path);
    }

    private String resolverAlias(KeyStore ks, String path) throws Exception {
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String candidate = aliases.nextElement();
            if (ks.isKeyEntry(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Nenhuma chave privada no KeyStore: " + path);
    }
}
