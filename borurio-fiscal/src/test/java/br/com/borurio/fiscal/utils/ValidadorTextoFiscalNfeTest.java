package br.com.borurio.fiscal.utils;

import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe.Campo;
import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe.Motivo;
import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe.Violacao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validação preventiva de texto para campos NF-e TString — charset U+0020..U+00FF (NT 2023.002)
 * + maxLength oficial. Escopo V1: só isso.
 */
@DisplayName("ValidadorTextoFiscalNfe — charset e maxLength dos campos TString da NF-e")
class ValidadorTextoFiscalNfeTest {

    @Test
    @DisplayName("null e vazio não são violação (presença é responsabilidade de outra camada)")
    void nullEVazio_semViolacao() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD, null).isEmpty());
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.INF_CPL, "").isEmpty());
    }

    @Test
    @DisplayName("ASCII imprimível comum é aceito")
    void asciiValido_aceito() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD,
                "Frasco spray 100ML (caixa) - prata espacial").isEmpty());
    }

    @Test
    @DisplayName("acentuação do português está dentro de U+0020..U+00FF e é aceita")
    void acentosPtBr_aceitos() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "Coração de melão à vontade çãõáéíóúàüÜ").isEmpty());
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_NOME, "José Antônio da Conceição Júnior").isEmpty());
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_MUN, "São Paulo").isEmpty());
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_LGR, "Rua Nossa Senhora da Conceição").isEmpty());
    }

    @Test
    @DisplayName("ideograma chinês em xProd → CARACTERE_NAO_PERMITIDO")
    void chines_recusado() {
        Optional<Violacao> v = ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "1喷油瓶-100ML（彩盒）-太空银");
        assertTrue(v.isPresent());
        assertEquals(Motivo.CARACTERE_NAO_PERMITIDO, v.get().motivo());
        assertEquals(Campo.X_PROD, v.get().campo());
        assertEquals(120, v.get().maxLength());
    }

    @Test
    @DisplayName("emoji (fora do BMP, par substituto) → CARACTERE_NAO_PERMITIDO")
    void emoji_recusado() {
        Optional<Violacao> v = ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "Produto legal 😀");
        assertTrue(v.isPresent());
        assertEquals(Motivo.CARACTERE_NAO_PERMITIDO, v.get().motivo());
    }

    @Test
    @DisplayName("aspa tipográfica U+2019 e travessão U+2014 → CARACTERE_NAO_PERMITIDO")
    void pontuacaoUnicodeForaDaFaixa_recusada() {
        assertEquals(Motivo.CARACTERE_NAO_PERMITIDO,
                ValidadorTextoFiscalNfe.validar(Campo.NAT_OP, "Venda — mercadoria").get().motivo());
        assertEquals(Motivo.CARACTERE_NAO_PERMITIDO,
                ValidadorTextoFiscalNfe.validar(Campo.NAT_OP, "Cliente’s order").get().motivo());
    }

    @Test
    @DisplayName("caractere de controle abaixo de U+0020 (tab) → CARACTERE_NAO_PERMITIDO")
    void controlChar_recusado() {
        assertEquals(Motivo.CARACTERE_NAO_PERMITIDO,
                ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "linha1\tlinha2").get().motivo());
    }

    @Test
    @DisplayName("começa ou termina com espaço → ESPACO_NA_BORDA")
    void espacoNaBorda_recusado() {
        assertEquals(Motivo.ESPACO_NA_BORDA,
                ValidadorTextoFiscalNfe.validar(Campo.X_PROD, " Produto").get().motivo());
        assertEquals(Motivo.ESPACO_NA_BORDA,
                ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "Produto ").get().motivo());
        assertEquals(Motivo.ESPACO_NA_BORDA,
                ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "   ").get().motivo());
    }

    @Test
    @DisplayName("espaço interno é permitido")
    void espacoInterno_aceito() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "Produto com espaco interno").isEmpty());
    }

    @Test
    @DisplayName("xProd no limite (120) aceito; 121 → ACIMA_DO_MAX_LENGTH")
    void maxLengthXProd() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "a".repeat(120)).isEmpty());
        assertEquals(Motivo.ACIMA_DO_MAX_LENGTH,
                ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "a".repeat(121)).get().motivo());
    }

    @Test
    @DisplayName("natOp/xNome no limite (60) aceito; 61 rejeitado")
    void maxLength60() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.NAT_OP, "a".repeat(60)).isEmpty());
        assertEquals(Motivo.ACIMA_DO_MAX_LENGTH,
                ValidadorTextoFiscalNfe.validar(Campo.X_NOME, "a".repeat(61)).get().motivo());
    }

    @Test
    @DisplayName("infCpl aceita até 5000 caracteres")
    void maxLengthInfCpl() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.INF_CPL, "a".repeat(5000)).isEmpty());
        assertEquals(Motivo.ACIMA_DO_MAX_LENGTH,
                ValidadorTextoFiscalNfe.validar(Campo.INF_CPL, "a".repeat(5001)).get().motivo());
    }

    @Test
    @DisplayName("caractere Latin-1 no limite superior (ÿ = U+00FF) é aceito")
    void limiteSuperiorFaixa_aceito() {
        assertTrue(ValidadorTextoFiscalNfe.validar(Campo.X_PROD, "abcÿdef").isEmpty());
    }

    @Test
    @DisplayName("maxLength de cada Campo corresponde ao XSD oficial")
    void maxLengthPorCampo() {
        assertEquals(120, Campo.X_PROD.maxLength());
        assertEquals(60, Campo.NAT_OP.maxLength());
        assertEquals(60, Campo.X_NOME.maxLength());
        assertEquals(60, Campo.X_LGR.maxLength());
        assertEquals(60, Campo.NRO.maxLength());
        assertEquals(60, Campo.X_BAIRRO.maxLength());
        assertEquals(60, Campo.X_MUN.maxLength());
        assertEquals(5000, Campo.INF_CPL.maxLength());
    }
}
