package br.com.borurio.fiscal.support;

import org.junit.jupiter.api.Assumptions;

public final class TestResourceSupport {

    private TestResourceSupport() {
    }

    public static void assumeTestCertificateAvailable() {
        boolean disponivel = Thread.currentThread()
                .getContextClassLoader()
                .getResource("cert/test-cert.pfx") != null;

        Assumptions.assumeTrue(
                disponivel,
                "Ignorando teste: certificado de teste nao disponivel no classpath (cert/test-cert.pfx)."
        );
    }
}
