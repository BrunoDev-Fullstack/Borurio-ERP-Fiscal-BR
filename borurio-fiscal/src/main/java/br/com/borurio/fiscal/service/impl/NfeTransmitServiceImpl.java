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
 * CONFIGURAÇÕES REQUERIDAS (application-dev.yml - módulo borurio-web)
 *
 * sefaz:
 *   url-autorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
 *   url-retorno: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeRetAutorizacao4.asmx
 *
 * fiscal:
 *   cert:
 *     path: C:/Projetos/borurio-erp-br/borurio-fiscal/src/main/resources/certs/generic-dev-cert.pfx
 *     pass: 1234
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
 * Versão: 3.2 (Sprint Fiscal – Integração SEFAZ-SP)
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

    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {
        NfeLog logFiscal = NfeLog.builder()
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

            log.info("NF-e transmitida com sucesso. CNPJ: {}", cnpjEmitente);
            return respostaSefaz;

        } catch (Exception ex) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Falha na transmissão NF-e: " + ex.getMessage());
            logFiscal.setXmlRetorno(null);
            logFiscal.setDataEvento(LocalDateTime.now());
            nfeLogMapper.insertLog(logFiscal);

            log.error("Erro ao transmitir NF-e para SEFAZ-SP: {}", ex.getMessage(), ex);
            return null;
        }
    }

    @Override
    public String consultarStatus() {
        try {
            log.info("Consultando status do serviço SEFAZ-SP em {}", sefazUrlAutorizacao);
            return "Serviço NF-e ativo (mock SEFAZ-SP)";
        } catch (Exception e) {
            log.error("Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return "Serviço NF-e indisponível";
        }
    }

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

    /**
     * Envia o envelope SOAP via HTTPS ao endpoint da SEFAZ-SP,
     * utilizando o certificado A1 já carregado via CertificadoService.
     */
    private String enviarSoap(String soapEnvelope) throws IOException {
        URL url = new URL(sefazUrlAutorizacao);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // Configuração SSL obtida do serviço de certificado já inicializado
        connection.setSSLSocketFactory(certificadoService.getSslContext().getSocketFactory());

        // Configurações HTTP
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
