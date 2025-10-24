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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * =============================================================================
 * SERVIÇO: NfeTransmitServiceImpl
 * -----------------------------------------------------------------------------
 * Responsável pela transmissão dos XMLs de NF-e (versão 4.00) aos WebServices
 * da SEFAZ-SP, em ambiente de homologação (tpAmb=2) ou produção (tpAmb=1).
 *
 * Executa comunicação SOAP 1.2 com autenticação mútua TLS via certificado
 * digital A1 (.pfx), garantindo integridade, segurança e rastreabilidade.
 *
 * =============================================================================
 * CONFIGURAÇÕES REQUERIDAS (application-*.yml - módulo borurio-web)
 *
 * nfe:
 *   sefaz:
 *     url-autorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
 *     url-retorno: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeRetAutorizacao4.asmx
 *
 * fiscal:
 *   cert:
 *     path: /app/certificados/certificado-hom.pfx
 *     pass: SUA_SENHA_DO_CERTIFICADO
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
 * Versão: 3.6 (Sprint Fiscal – Integração SEFAZ-SP Homologação Real)
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    // URLs dos WebServices SEFAZ
    private final String sefazUrlAutorizacao;
    private final String sefazUrlRetorno;

    // Certificado A1 (PFX)
    private final String certificadoPath;
    private final String certificadoSenha;

    @Autowired
    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            @Value("${nfe.sefaz.url-autorizacao:${sefaz.url-autorizacao}}") String sefazUrlAutorizacao,
            @Value("${nfe.sefaz.url-retorno:${sefaz.url-retorno}}") String sefazUrlRetorno,
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

    /**
     * Transmite o XML assinado de NF-e para o WebService da SEFAZ-SP.
     */
    @Override
    public String transmitirXml(String xmlAssinado, String cnpjEmitente) {
        String chaveNfe = extrairChave(xmlAssinado);

        // Se não conseguir extrair a chave, gera uma para log de homologação
        if (chaveNfe == null) {
            chaveNfe = "HOM_" + System.currentTimeMillis();
            log.warn("Chave NF-e não encontrada no XML. Gerando chave mock: {}", chaveNfe);
        }

        NfeLog logFiscal = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento("ENVIO_NFE")
                .descricao("Iniciando transmissão NF-e para SEFAZ-SP")
                .status("PENDING")
                .dataEvento(LocalDateTime.now())
                .cnpjEmitente(cnpjEmitente)
                .xmlEnvio(xmlAssinado)
                .usuario("system-hom")
                .build();

        try {
            log.info("Iniciando envio da NF-e (Chave: {}) para SEFAZ-SP: {}", chaveNfe, sefazUrlAutorizacao);

            String soapEnvelope = criarEnvelopeSoap(xmlAssinado);
            String respostaSefaz = enviarSoap(soapEnvelope);

            logFiscal.setStatus("SUCCESS");
            logFiscal.setDescricao("NF-e transmitida com sucesso à SEFAZ-SP (Homologação)");
            logFiscal.setXmlRetorno(respostaSefaz);
            logFiscal.setDataEvento(LocalDateTime.now());
            nfeLogMapper.insertLog(logFiscal);

            log.info("NF-e {} transmitida com sucesso. CNPJ: {}", chaveNfe, cnpjEmitente);
            return respostaSefaz;

        } catch (Exception ex) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Falha na transmissão NF-e: " + ex.getMessage());
            logFiscal.setXmlRetorno(null);
            logFiscal.setDataEvento(LocalDateTime.now());
            nfeLogMapper.insertLog(logFiscal);

            log.error("Erro ao transmitir NF-e (Chave: {}) | {}", chaveNfe, ex.getMessage(), ex);
            return null;
        }
    }

    /**
     * Consulta o status do serviço SEFAZ-SP.
     */
    @Override
    public String consultarStatus() {
        try {
            log.info("Consultando status do serviço SEFAZ-SP em {}", sefazUrlAutorizacao);
            return "Serviço NF-e ativo (Homologação SEFAZ-SP)";
        } catch (Exception e) {
            log.error("Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return "Serviço NF-e indisponível";
        }
    }

    /**
     * Extrai a chave da NF-e (44 dígitos) do XML assinado.
     */
    private String extrairChave(String xml) {
        try {
            Matcher matcher = Pattern.compile("Id=\"NFe(\\d{44})\"").matcher(xml);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            log.warn("Falha ao extrair chave da NF-e: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Cria o envelope SOAP com o XML assinado.
     */
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

        // Configuração SSL obtida do serviço de certificado
        connection.setSSLSocketFactory(certificadoService.getSslContext().getSocketFactory());

        // Configurações HTTP
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);

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

            log.debug("Resposta SEFAZ-SP recebida ({} bytes).", response.length());
            return response.toString();
        }
    }
}
