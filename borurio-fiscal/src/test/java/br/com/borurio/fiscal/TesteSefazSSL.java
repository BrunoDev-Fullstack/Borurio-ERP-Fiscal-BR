package br.com.borurio.fiscal;

import br.com.borurio.fiscal.config.SslConfig;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

public class TesteSefazSSL {

    @Test
    void deveConectarNaSefazComCertificado() throws Exception {

        SSLContext sslContext = SslConfig.criarSSLContext();

        assertNotNull(sslContext, "SSLContext não pode ser null");

        HttpsURLConnection.setDefaultSSLSocketFactory(
                sslContext.getSocketFactory()
        );

        URL url = new URL("https://homologacao.nfe.fazenda.sp.gov.br");

        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        conn.connect();

        int responseCode = conn.getResponseCode();

        System.out.println("HTTP STATUS: " + responseCode);

        // ✔ VALIDAÇÃO CORRETA
        assertTrue(
                responseCode == 200 || responseCode == 403,
                "Resposta inesperada da SEFAZ: " + responseCode
        );

        // ✔ valida que realmente conectou
        assertTrue(responseCode > 0, "Falha na conexão HTTPS");

    }
}