package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.config.SefazRotaResolver;
import br.com.borurio.fiscal.config.SefazRotasProperties;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.CertificadoService;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Fase 0 do Gate SVC (14-08-2026) — prova que a UF passada por parâmetro decide a rota (nunca
 * mais um {@code @Value} fixo) e que UF sem rota configurada falha ANTES de qualquer interação
 * com certificado/rede/log — nenhuma chamada de rede acontece para uma UF não configurada.
 */
@ExtendWith(MockitoExtension.class)
class NfeTransmitServiceImplTest {

    @Mock NfeLogMapper nfeLogMapper;
    @Mock CertificadoService certificadoService;
    @Mock Retry sefazRetry;

    private NfeTransmitServiceImpl construir(String... ufsComRota) {
        SefazRotasProperties.Rota rota = new SefazRotasProperties.Rota();
        rota.setAutorizacao("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx");
        rota.setRetorno("https://homologacao.nfe.fazenda.sp.gov.br/ws/nferetautorizacao4.asmx");
        rota.setConsulta("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx");
        rota.setStatus("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx");

        java.util.Map<String, SefazRotasProperties.Rota> mapa = new java.util.LinkedHashMap<>();
        for (String uf : ufsComRota) mapa.put(uf, rota);

        SefazRotasProperties props = new SefazRotasProperties();
        props.setRotas(mapa);

        return new NfeTransmitServiceImpl(nfeLogMapper, certificadoService, sefazRetry, new SefazRotaResolver(props));
    }

    @Test
    @DisplayName("UF sem rota configurada (MG): transmitirXml falha ANTES de tocar certificado/log — "
            + "nenhuma chamada de rede, mesmo endpoint de SP não é usado por engano")
    void transmitirXml_ufSemRota_falhaFechadoSemInteracao() {
        NfeTransmitServiceImpl service = construir("SP");

        assertThrows(br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException.class,
                () -> service.transmitirXml("<NFe/>", "12345678000195", "MG", 2));

        verifyNoInteractions(certificadoService);
        verifyNoInteractions(nfeLogMapper);
        verifyNoInteractions(sefazRetry);
    }

    @Test
    @DisplayName("consultarNfe com UF sem rota configurada falha fechado antes de qualquer interação")
    void consultarNfe_ufSemRota_falhaFechadoSemInteracao() {
        NfeTransmitServiceImpl service = construir("SP");

        assertThrows(br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException.class,
                () -> service.consultarNfe("35260412345678000195550010000000011000000010", "MG", 2));

        verifyNoInteractions(certificadoService);
        verifyNoInteractions(nfeLogMapper);
    }

    @Test
    @DisplayName("consultarStatus com UF sem rota configurada falha fechado antes de qualquer interação")
    void consultarStatus_ufSemRota_falhaFechadoSemInteracao() {
        NfeTransmitServiceImpl service = construir("SP");

        assertThrows(br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException.class, () -> service.consultarStatus("MG", 2));

        verifyNoInteractions(certificadoService);
        verifyNoInteractions(nfeLogMapper);
    }

    @Test
    @DisplayName("Nenhuma UF configurada: SP também falha fechado (sem rota nenhuma cadastrada)")
    void semNenhumaRotaConfigurada_spTambemFalha() {
        NfeTransmitServiceImpl service = construir(); // mapa vazio

        assertThrows(br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException.class,
                () -> service.transmitirXml("<NFe/>", "12345678000195", "SP", 2));
    }
}
