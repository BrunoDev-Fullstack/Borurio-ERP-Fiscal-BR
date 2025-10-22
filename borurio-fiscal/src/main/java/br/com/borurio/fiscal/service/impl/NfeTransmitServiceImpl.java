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
 * Responsável pela transmissão de XMLs NF-e (versão 4.00) aos WebServices SEFAZ-SP.
 * Utiliza comunicação SOAP 1.2 + TLS mútua com certificado A1 (.pfx).
 *
 * Ambientes suportados:
 *  - Homologação (tpAmb=2)
 *  - Produção (tpAmb=1)
 *
 * =============================================================================
 * CONFIGURAÇÃO REQUERIDA (application-<perfil>.yml):
 *
 * borurio:
 *   sefaz:
 *     urlAutorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeAutorizacao4.asmx
 *     urlRetAutorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/NFeRetAutorizacao4.asmx
 *   certificado:
 *     caminho: certs/borurio-hom.pfx
 *     senha: SENHA_DO_CERTIFICADO
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Versão: 3.3 (Homologação SEFAZ-SP)
 * =============================================================================
 */
@Slf4j
@Service
public class NfeTransmitServiceImpl implements NfeTransmitService {

    private final NfeLogMapper nfeLogMapper;
    private final CertificadoService certificadoService;

    // URLs SEFAZ (injeção via application-hom.yml)
    private final String sefazUrlAutorizacao;
    private final String sefazUrlRetAutorizacao;
    private final String sefazUrlStatusServico;
    private final String sefazUrlConsultaProtocolo;
    private final String sefazUrlInutilizacao;
    private final String sefazUrlRecepcaoEvento;

    // Certificado digital (injeção via application-hom.yml)
    private final String certificadoCaminho;
    private final String certificadoSenha;

    @Autowired
    public NfeTransmitServiceImpl(
            NfeLogMapper nfeLogMapper,
            CertificadoService certificadoService,
            @Value("${borurio.sefaz.urlAutorizacao}") String sefazUrlAutorizacao,
            @Value("${borurio.sefaz.urlRetAutorizacao}") String sefazUrlRetAutorizacao,
            @Value("${borurio.sefaz.urlStatusServico}") String sefazUrlStatusServico,
            @Value("${borurio.sefaz.urlConsultaProtocolo}") String sefazUrlConsultaProtocolo,
            @Value("${borurio.sefaz.urlInutilizacao}") String sefazUrlInutilizacao,
            @Value("${borurio.sefaz.urlRecepcaoEvento}") String sefazUrlRecepcaoEvento,
            @Value("${borurio.certificado.caminho}") String certificadoCaminho,
            @Value("${borurio.certificado.senha}") String certificadoSenha
    ) {
        this.nfeLogMapper = nfeLogMapper;
        this.certificadoService = certificadoService;
        this.sefazUrlAutorizacao = sefazUrlAutorizacao;
        this.sefazUrlRetAutorizacao = sefazUrlRetAutorizacao;
        this.sefazUrlStatusServico = sefazUrlStatusServico;
        this.sefazUrlConsultaProtocolo = sefazUrlConsultaProtocolo;
        this.sefazUrlInutilizacao = sefazUrlInutilizacao;
        this.sefazUrlRecepcaoEvento = sefazUrlRecepcaoEvento;
        this.certificadoCaminho = certificadoCaminho;
        this.certificadoSenha = certificadoSenha;
    }

    // =========================================================================
    // ENVIO DE NF-E
    // =========================================================================
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

            log.info("[NF-e] Transmissão bem-sucedida para SEFAZ-SP — CNPJ: {}", cnpjEmitente);
            return respostaSefaz;

        } catch (Exception ex) {
            logFiscal.setStatus("ERROR");
            logFiscal.setDescricao("Falha na transmissão NF-e: " + ex.getMessage());
            logFiscal.setDataEvento(LocalDateTime.now());
            nfeLogMapper.insertLog(logFiscal);

            log.error("[NF-e] Erro ao transmitir NF-e para SEFAZ-SP: {}", ex.getMessage(), ex);
            return null;
        }
    }

    // =========================================================================
    // CONSULTA DE STATUS
    // =========================================================================
    @Override
    public String consultarStatus() {
        try {
            log.info("[NF-e] Consultando status do serviço SEFAZ-SP em {}", sefazUrlStatusServico);
            return "Serviço NF-e ativo (mock SEFAZ-SP)";
        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return "Serviço NF-e indisponível";
        }
    }

    // =========================================================================
    // UTILITÁRIOS INTERNOS
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

    /**
     * Envia o envelope SOAP via HTTPS com autenticação mútua (TLS) para SEFAZ-SP.
     */
    private String enviarSoap(String soapEnvelope) throws IOException {
        URL url = new URL(sefazUrlAutorizacao);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        // SSL do certificado A1 carregado via CertificadoService
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

        // Leitura da resposta SEFAZ
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
