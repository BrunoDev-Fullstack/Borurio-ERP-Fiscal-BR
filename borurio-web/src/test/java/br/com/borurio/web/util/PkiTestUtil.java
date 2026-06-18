package br.com.borurio.web.util;

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;

/**
 * Gera PKCS12 sintético em memória para testes de unidade.
 * Não cria arquivos em disco; não depende de keytool; não usa APIs internas do JDK.
 */
public final class PkiTestUtil {

    private PkiTestUtil() {}

    /**
     * Gera bytes PKCS12 com par RSA-2048 e certificado X.509 autoassinado.
     *
     * @param dn           Distinguished Name no formato RFC 2253 (ex: "CN=EMPRESA:12345678000195,O=ICP-Brasil,C=BR")
     * @param senha        senha do KeyStore e da chave privada
     * @param validityDays dias de validade a partir de agora
     */
    public static byte[] gerarPkcs12(String dn, String senha, int validityDays) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        Instant now = Instant.now();
        X500Name subject = new X500Name(dn);

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .build(kp.getPrivate());

        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(
                new JcaX509v3CertificateBuilder(
                        subject,
                        BigInteger.valueOf(System.currentTimeMillis()),
                        Date.from(now.minus(1, ChronoUnit.DAYS)),
                        Date.from(now.plus(validityDays, ChronoUnit.DAYS)),
                        subject,
                        kp.getPublic()
                ).build(signer)
        );

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("test", kp.getPrivate(), senha.toCharArray(), new Certificate[]{cert});

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ks.store(out, senha.toCharArray());
        return out.toByteArray();
    }

    public static String gerarPkcs12Base64(String dn, String senha, int validityDays) throws Exception {
        return Base64.getEncoder().encodeToString(gerarPkcs12(dn, senha, validityDays));
    }
}
