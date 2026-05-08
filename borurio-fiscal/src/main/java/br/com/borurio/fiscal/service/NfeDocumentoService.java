package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.mapper.NfeDocumentoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

/**
 * Gerencia o ciclo de vida do estado da NF-e na tabela nfe_documento.
 *
 * Responsabilidades:
 *   - Salvar o documento NF-e no momento da transmissão
 *   - Atualizar cStat/nProt após retorno SEFAZ
 *   - Consultar documentos por chave, série/número ou emitente
 */
@Service
public class NfeDocumentoService {

    private static final Logger log = LoggerFactory.getLogger(NfeDocumentoService.class);

    private static final DateTimeFormatter DH_RECBTO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final NfeDocumentoMapper mapper;

    public NfeDocumentoService(NfeDocumentoMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Persiste o documento e aplica o retorno SEFAZ em uma única operação.
     * Chamado logo após NfeOrquestradorService.processar().
     */
    public NfeDocumento salvarComRetorno(
            String chaveNfe,
            String nNf,
            String serie,
            String cnpjEmitente,
            String destCnpjCpf,
            String destRazaoSocial,
            BigDecimal valorTotal,
            int tpAmb,
            LocalDateTime dataEmissao,
            String xmlNfeAssinado,
            NfeSefazRetorno retorno) {

        NfeDocumento doc = NfeDocumento.builder()
                .chaveNfe(chaveNfe)
                .nNf(nNf)
                .serie(serie)
                .cnpjEmitente(cnpjEmitente)
                .destCnpjCpf(destCnpjCpf)
                .destRazaoSocial(destRazaoSocial)
                .valorTotal(valorTotal)
                .tpAmb(tpAmb)
                .cStat(String.valueOf(retorno.getCStat()))
                .xMotivo(retorno.getXMotivo())
                .nProt(retorno.getNProt())
                .dhRecbto(parseDhRecbto(retorno.getDhRecbto()))
                .xmlNfe(xmlNfeAssinado)
                .dataEmissao(dataEmissao)
                .build();

        try {
            Optional<NfeDocumento> existente = mapper.findByChave(chaveNfe);
            if (existente.isPresent()) {
                doc.setXmlProtocolo(existente.get().getXmlProtocolo());
                mapper.updateStatus(doc);
                log.info("[NfeDocumento] Status atualizado | chave={} | cStat={} | nProt={}",
                        chaveNfe, doc.getCStat(), retorno.getNProt());
            } else {
                mapper.insert(doc);
                log.info("[NfeDocumento] Documento persistido | chave={} | cStat={} | nProt={}",
                        chaveNfe, doc.getCStat(), retorno.getNProt());
            }
        } catch (Exception e) {
            // Falha de persistência nunca interrompe o fluxo fiscal principal
            log.error("[NfeDocumento] Falha ao persistir | chave={} | erro={}", chaveNfe, e.getMessage());
        }

        return doc;
    }

    public Optional<NfeDocumento> buscarPorChave(String chaveNfe) {
        return mapper.findByChave(chaveNfe);
    }

    public List<NfeDocumento> listarPorEmitente(String cnpjEmitente, int limite) {
        return mapper.findByEmitente(cnpjEmitente, limite > 0 ? limite : 50);
    }

    public Optional<NfeDocumento> buscarPorSerieNumero(String cnpjEmitente, String serie, String nNf) {
        return mapper.findBySerieNumero(cnpjEmitente, serie, nNf);
    }

    private LocalDateTime parseDhRecbto(String dhRecbto) {
        if (dhRecbto == null || dhRecbto.isBlank()) return null;
        try {
            return LocalDateTime.parse(dhRecbto, DH_RECBTO_FMT);
        } catch (DateTimeParseException e) {
            log.debug("[NfeDocumento] dhRecbto em formato inesperado: {}", dhRecbto);
            return null;
        }
    }
}
