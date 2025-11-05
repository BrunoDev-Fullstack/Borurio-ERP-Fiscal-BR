package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.CertificadoService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * =============================================================================
 * SERVIÇO: NfeTransmitServiceImpl
 * -----------------------------------------------------------------------------
 * Responsável pela transmissão dos XMLs de NF-e (versão 4.00) aos WebServices
 * da SEFAZ-SP, em ambiente de homologação (tpAmb=2) ou produção (tpAmb=1).
 *
 * Executa comunicação SOAP 1.2 com autenticação mútua TLS via certificado
 * digital A1 (.pfx), garantindo integridade e rastreabilidade.
 *
 * =============================================================================
 * CONFIGURAÇÕES REQUERIDAS (application-*.yml)
 *
 * sefaz:
 *   url-autorizacao: https://nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
 *   url-retorno:     https://nfe.fazenda.sp.gov.br/ws/NFeRetAutorizacao4.asmx
 *
 * fiscal:
 *   cert:
 *     path: /app/certificados/certificado-prd.pfx
 *     pass: SENHA_DO_CERTIFICADO_REAL
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Versão: 3.4 (Sprint Fiscal – Integração SEFAZ-SP)
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;
    private final String sefazUrlAutorizacao;
    private final String sefazUrlRetorno;
    private final String certificadoPath;
    private final String certificadoSenha;

    @Autowired
    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            @Value("${sefaz.url-autorizacao}") String sefazUrlAutorizacao,
            @Value("${sefaz.url-retorno}") String sefazUrlRetorno,
            @Value("${fiscal.cert.path}") String certificadoPath,
            @Value("${fiscal.cert.pass}") String certificadoSenha
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
        this.sefazUrlAutorizacao = sefazUrlAutorizacao;
        this.sefazUrlRetorno = sefazUrlRetorno;
        this.certificadoPath = certificadoPath;
        this.certificadoSenha = certificadoSenha;
    }

    // =========================================================================
    // MÉTODO PRINCIPAL: Transmissão de NF-e
    // =========================================================================
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {

        // Extração segura da chave NF-e
        String chaveNFe = extrairChaveNFe(xmlAssinado);

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNFe)
                .tipoEvento("ENVIO_NFE")
                .descricao("Iniciando transmissão NF-e para SEFAZ-SP")
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system")
                .build();

        try {
            String soapEnvelope = criarEnvelopeSoap(xmlAssinado);
            String respostaSefaz = enviarSoap(soapEnvelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso para SEFAZ-SP");
            logFiscal.setXmlRetorno(respostaSefaz);
            logFiscal.setDataEvento(LocalDateTime.now());
            nfeLogMapper.insertLog(logFiscal);

            log.info("[NfeTransmitServiceImpl] NF-e transmitida com sucesso. CNPJ: {}", cnpjEmitente);
            return respostaSefaz;

        } catch (Exception ex) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Falha na transmissão NF-e: " + ex.getMessage());
            logFiscal.setXmlRetorno(null);
            logFiscal.setDataEvento(LocalDateTime.now());

            try {
                nfeLogMapper.insertLog(logFiscal);
            } catch (Exception e2) {
                log.error("[NfeTransmitServiceImpl] Falha ao registrar log fiscal: {}", e2.getMessage());
            }

            log.error("[NfeTransmitServiceImpl] Erro ao transmitir NF-e para SEFAZ-SP: {}", ex.getMessage(), ex);
            return "<erro>Falha ao transmitir NF-e: " + ex.getMessage() + "</erro>";
        }
    }

    // =========================================================================
    // CONSULTA DE STATUS DO SERVIÇO SEFAZ-SP
    // =========================================================================
    @Override
    public String consultarStatus() {
        try {
            log.info("[NfeTransmitServiceImpl] Consultando status do serviço SEFAZ-SP em {}", sefazUrlAutorizacao);
            // Em homologação, retorna um stub simulado
            return "Serviço SEFAZ-SP disponível para consulta (stub local).";
        } catch (Exception e) {
            log.error("[NfeTransmitServiceImpl] Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return "Serviço SEFAZ-SP indisponível.";
        }
    }

    // =========================================================================
    // MÉTODO AUXILIAR: Extração da chave NF-e (Id da tag <infNFe>)
    // =========================================================================
    private String extrairChaveNFe(String xml) {
        try {
            if (xml != null && xml.contains("Id=\"NFe")) {
                int start = xml.indexOf("Id=\"NFe") + 4;
                int end = xml.indexOf("\"", start);
                String chave = xml.substring(start, end).replace("NFe", "").trim();
                if (!chave.isBlank()) return chave;
            }
        } catch (Exception e) {
            log.warn("[NfeTransmitServiceImpl] Erro ao extrair chave NF-e: {}", e.getMessage());
        }
        return "NFe-SEM-CHAVE";
    }

    // =========================================================================
    // CRIAÇÃO DO ENVELOPE SOAP 1.2
    // =========================================================================
    private String criarEnvelopeSoap(String xmlAssinado) {
        return """
            <soap12:Envelope xmlns:soap12="http://www.w3.org/2003/05/soap-envelope">
              <soap12:Body>
                <nfeDadosMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
                  """ + xmlAssinado + """
                </nfeDadosMsg>
              </soap12:Body>
            </soap12:Envelope>
            """;
    }

    // =========================================================================
    // ENVIO DO ENVELOPE VIA HTTPS
    // =========================================================================
    private String enviarSoap(String soapEnvelope) throws IOException {
        URL url = new URL(sefazUrlAutorizacao);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        try {
            SSLContext sslContext = certificadoService.getSslContext();
            if (sslContext != null) {
                connection.setSSLSocketFactory(sslContext.getSocketFactory());
                log.debug("[NfeTransmitServiceImpl] SSLContext configurado com certificado A1.");
            } else {
                log.warn("[NfeTransmitServiceImpl] SSLContext nulo — execução sem autenticação mútua.");
            }
        } catch (Exception e) {
            log.error("[NfeTransmitServiceImpl] Erro ao inicializar SSLContext: {}", e.getMessage(), e);
        }

        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);

        try (OutputStream os = connection.getOutputStream()) {
            os.write(soapEnvelope.getBytes(StandardCharsets.UTF_8));
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line.trim());
            }
            return response.toString();
        }
    }
}
