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
 * Implementação do serviço de transmissão de XMLs de NF-e (versão 4.00)
 * para os WebServices da SEFAZ-SP (ambiente de homologação ou produção).
 *
 * Este componente realiza o envio real via protocolo SOAP 1.2,
 * utilizando autenticação mútua TLS com certificado digital A1 (.pfx),
 * garantindo segurança e rastreabilidade em conformidade com as
 * normas técnicas da SEFAZ.
 *
 * Boas práticas aplicadas:
 * - Injeção segura de dependências (Spring Boot).
 * - Utilização de SSLContext fornecido por CertificadoService.
 * - Registro de logs completos em banco via MyBatis (tabela nfe_log).
 * - Timeout controlado e charset UTF-8.
 * - Código limpo, rastreável e compatível com Java 17 / Spring Boot 3.3.x.
 *
 * Configuração esperada em application-dev.yml:
 *
 * sefaz:
 *   url-autorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
 *
 * fiscal:
 *   cert:
 *     path: C:/Projetos/borurio-erp-br/borurio-fiscal/src/main/resources/certs/generic-dev-cert.pfx
 *     pass: ${CERT_PASS}
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Java Fullstack
 * Graduação: Cyber Security
 * Sprint: Fiscal 2.2 (Integração SEFAZ-SP / NF-e 4.00)
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;
    private final String sefazUrlAutorizacao;

    /**
     * Construtor principal com injeção de dependências.
     *
     * @param nfeLogMapper         Mapper responsável pela persistência dos logs fiscais.
     * @param certificadoService   Serviço responsável pelo contexto SSL do certificado A1.
     * @param sefazUrlAutorizacao  URL de autorização configurada no application-dev.yml (homologação ou produção).
     */
    @Autowired
    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            @Value("${sefaz.url-autorizacao}") String sefazUrlAutorizacao
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
        this.sefazUrlAutorizacao = sefazUrlAutorizacao;
    }

    /**
     * Transmite o XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * Fluxo técnico:
     * 1. Monta o envelope SOAP com o XML assinado.
     * 2. Envia via HTTPS para o endpoint SEFAZ configurado.
     * 3. Registra log detalhado da operação no banco de dados.
     *
     * @param xmlAssinado  XML completo e assinado digitalmente.
     * @param cnpjEmitente CNPJ do emitente responsável pela NF-e.
     * @return XML de resposta SOAP retornado pela SEFAZ-SP.
     */
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

    /**
     * Implementação do health check fiscal.
     * Retorna o status do serviço de autorização da SEFAZ-SP.
     *
     * @return Status simulado ou real (mock SEFAZ-SP)
     */
    @Override
    public String consultarStatus() {
        try {
            // Futuramente: implementar consulta real via serviço NfeStatusServico4
            log.info("Consultando status do serviço SEFAZ-SP em {}", sefazUrlAutorizacao);
            return "Serviço NF-e ativo (mock SEFAZ-SP)";
        } catch (Exception e) {
            log.error("Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return "Serviço NF-e indisponível";
        }
    }

    /**
     * Cria o envelope SOAP contendo o XML da NF-e assinado.
     *
     * @param xmlAssinado XML completo e assinado digitalmente.
     * @return Envelope SOAP pronto para envio.
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
     * Envia o envelope SOAP via HTTPS para o endpoint da SEFAZ-SP.
     *
     * O método realiza comunicação síncrona (bloqueante)
     * e aplica autenticação mútua via certificado A1 (.pfx).
     *
     * @param soapEnvelope Envelope SOAP contendo a NF-e.
     * @return XML de resposta retornado pela SEFAZ-SP.
     * @throws IOException Em caso de falha de rede ou protocolo.
     */
    private String enviarSoap(String soapEnvelope) throws IOException {
        URL url = new URL(sefazUrlAutorizacao);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // Configuração do SSL com certificado digital A1 (.pfx)
        connection.setSSLSocketFactory(certificadoService.getSslContext().getSocketFactory());

        // Configurações HTTP
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);

        // Envio do envelope SOAP
        try (OutputStream os = connection.getOutputStream()) {
            os.write(soapEnvelope.getBytes(StandardCharsets.UTF_8));
        }

        // Leitura da resposta
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
