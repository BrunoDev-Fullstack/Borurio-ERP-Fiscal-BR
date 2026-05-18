package br.com.borurio.fiscal.danfe;

import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.NoSuchElementException;

@Service
public class DanfeServiceImpl implements DanfeService {

    private static final Logger log = LoggerFactory.getLogger(DanfeServiceImpl.class);
    private static final DateTimeFormatter DH_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final NfeDocumentoService nfeDocumentoService;
    private final DanfeXmlParser      danfeXmlParser;
    private final DanfePdfGenerator   danfePdfGenerator;

    public DanfeServiceImpl(NfeDocumentoService nfeDocumentoService,
                            DanfeXmlParser danfeXmlParser,
                            DanfePdfGenerator danfePdfGenerator) {
        this.nfeDocumentoService = nfeDocumentoService;
        this.danfeXmlParser      = danfeXmlParser;
        this.danfePdfGenerator   = danfePdfGenerator;
    }

    @Override
    public byte[] gerarDanfe(String chaveNfe) {
        NfeDocumento doc = nfeDocumentoService.buscarPorChave(chaveNfe)
                .orElseThrow(() -> new NoSuchElementException(
                        "NF-e não encontrada para a chave: " + chaveNfe));

        if (doc.getXmlNfe() == null || doc.getXmlNfe().isBlank()) {
            throw new IllegalStateException(
                    "XML da NF-e não disponível para a chave: " + chaveNfe);
        }

        String nProt    = doc.getNProt();
        String dhRecbto = doc.getDhRecbto() != null ? doc.getDhRecbto().format(DH_FMT) : null;
        String cStat    = doc.getCStat();

        log.info("[DANFE] Gerando | chave={} | cStat={}", chaveNfe, cStat);

        DanfeData data = danfeXmlParser.parse(doc.getXmlNfe(), nProt, dhRecbto, cStat, chaveNfe);
        return danfePdfGenerator.gerar(data);
    }
}
