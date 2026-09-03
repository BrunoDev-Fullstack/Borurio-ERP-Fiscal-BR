package br.com.borurio.fiscal.config;

import br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fase 0 do Gate SVC (14-08-2026) — roteamento fiscal por UF/autorizador. Só o autorizador
 * NORMAL existe nesta fase; fail-closed é a propriedade central: UF sem rota configurada nunca
 * cai silenciosamente em outra UF.
 */
class SefazRotaResolverTest {

    private SefazRotaResolver resolverComSP() {
        SefazRotasProperties.Rota rotaSp = new SefazRotasProperties.Rota();
        rotaSp.setAutorizacao("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx");
        rotaSp.setRetorno("https://homologacao.nfe.fazenda.sp.gov.br/ws/nferetautorizacao4.asmx");
        rotaSp.setConsulta("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx");
        rotaSp.setStatus("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx");

        SefazRotasProperties props = new SefazRotasProperties();
        props.setRotas(Map.of("SP", rotaSp));
        return new SefazRotaResolver(props);
    }

    @Test
    @DisplayName("UF=SP resolve para o endpoint configurado, exatamente como cadastrado")
    void resolveSpCorretamente() {
        SefazRotasProperties.Rota rota = resolverComSP().resolver("SP");

        assertEquals("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx", rota.getAutorizacao());
        assertEquals("https://homologacao.nfe.fazenda.sp.gov.br/ws/nferetautorizacao4.asmx", rota.getRetorno());
        assertEquals("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsultaprotocolo4.asmx", rota.getConsulta());
        assertEquals("https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx", rota.getStatus());
    }

    @Test
    @DisplayName("UF minúscula/com espaço normaliza para a mesma rota de SP")
    void normalizaUfAntesDeResolver() {
        SefazRotaResolver resolver = resolverComSP();
        assertSame(resolver.resolver("sp"), resolver.resolver(" SP "));
        assertSame(resolver.resolver("sp"), resolver.resolver("Sp"));
        assertSame(resolver.resolver("sp"), resolver.resolver(" sP "));
    }

    @Test
    @DisplayName("Banca 14-08-2026 (3ª rodada): canonicalizarUf é a única fonte de normalização "
            + "(Locale.ROOT, trim+upper) — outros chamadores (NfeGeracaoService, "
            + "NfeReconciliacaoService) reusam este método, nunca reimplementam por conta própria")
    void canonicalizarUf_trimEUppercaseComLocaleRoot() {
        assertEquals("SP", SefazRotaResolver.canonicalizarUf("sp"));
        assertEquals("SP", SefazRotaResolver.canonicalizarUf(" SP "));
        assertEquals("SP", SefazRotaResolver.canonicalizarUf("Sp"));
        assertEquals("SP", SefazRotaResolver.canonicalizarUf(" sp "));
        assertNull(SefazRotaResolver.canonicalizarUf(null), "não fabrica valor para entrada nula");
        assertEquals("", SefazRotaResolver.canonicalizarUf("   "), "não fabrica SP para entrada em branco");
    }

    @Test
    @DisplayName("UF sem rota configurada (MG) falha fechado — nunca cai para SP por omissão")
    void falhaFechadoParaUfSemRota() {
        SefazRotaResolver resolver = resolverComSP();

        // Tipo específico (achado de code review 14-08-2026), não IllegalStateException genérico
        // — precisa ser distinguível de qualquer catch(Exception) que envolva transporte.
        SefazRotaNaoConfiguradaException ex = assertThrows(
                SefazRotaNaoConfiguradaException.class, () -> resolver.resolver("MG"));

        assertTrue(ex.getMessage().contains("MG"), "mensagem precisa identificar a UF sem rota");
        assertFalse(ex.getMessage().contains("nfe.fazenda.sp.gov.br"),
                "erro de UF sem rota nunca pode mencionar/expor o endpoint de SP como se fosse usado");
    }

    @Test
    @DisplayName("UF nula ou em branco falha explicitamente, nunca resolve um valor default")
    void falhaParaUfNulaOuEmBranco() {
        SefazRotaResolver resolver = resolverComSP();
        assertThrows(IllegalArgumentException.class, () -> resolver.resolver(null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolver("   "));
    }

    @Test
    @DisplayName("Achado de code review 14-08-2026: UF com entrada no mapa mas rota incompleta "
            + "(endpoint em branco) falha fechado, nunca devolve Rota com campo null")
    void falhaFechadoParaRotaIncompleta() {
        SefazRotasProperties.Rota rotaIncompleta = new SefazRotasProperties.Rota();
        rotaIncompleta.setAutorizacao("https://homologacao.nfe.fazenda.mg.gov.br/ws/nfeautorizacao4.asmx");
        rotaIncompleta.setRetorno("https://homologacao.nfe.fazenda.mg.gov.br/ws/nferetautorizacao4.asmx");
        rotaIncompleta.setConsulta("https://homologacao.nfe.fazenda.mg.gov.br/ws/nfeconsultaprotocolo4.asmx");
        // status: esquecido de propósito

        Map<String, SefazRotasProperties.Rota> mapa = new LinkedHashMap<>();
        mapa.put("MG", rotaIncompleta);
        SefazRotasProperties props = new SefazRotasProperties();
        props.setRotas(mapa);
        SefazRotaResolver resolver = new SefazRotaResolver(props);

        SefazRotaNaoConfiguradaException ex = assertThrows(
                SefazRotaNaoConfiguradaException.class, () -> resolver.resolver("MG"));

        assertTrue(ex.getMessage().contains("status"), "mensagem precisa identificar o campo faltando");
        assertTrue(ex.getMessage().contains("MG"));
    }

    @Test
    @DisplayName("Rota com endpoint em branco (string vazia, não ausente) também falha fechado")
    void falhaFechadoParaRotaComCampoEmBranco() {
        SefazRotasProperties.Rota rotaComCampoVazio = new SefazRotasProperties.Rota();
        rotaComCampoVazio.setAutorizacao("https://homologacao.nfe.fazenda.rj.gov.br/ws/nfeautorizacao4.asmx");
        rotaComCampoVazio.setRetorno("https://homologacao.nfe.fazenda.rj.gov.br/ws/nferetautorizacao4.asmx");
        rotaComCampoVazio.setConsulta("   "); // em branco, não null
        rotaComCampoVazio.setStatus("https://homologacao.nfe.fazenda.rj.gov.br/ws/nfestatusservico4.asmx");

        Map<String, SefazRotasProperties.Rota> mapa = new LinkedHashMap<>();
        mapa.put("RJ", rotaComCampoVazio);
        SefazRotasProperties props = new SefazRotasProperties();
        props.setRotas(mapa);
        SefazRotaResolver resolver = new SefazRotaResolver(props);

        SefazRotaNaoConfiguradaException ex = assertThrows(
                SefazRotaNaoConfiguradaException.class, () -> resolver.resolver("RJ"));
        assertTrue(ex.getMessage().contains("consulta"));
    }
}
