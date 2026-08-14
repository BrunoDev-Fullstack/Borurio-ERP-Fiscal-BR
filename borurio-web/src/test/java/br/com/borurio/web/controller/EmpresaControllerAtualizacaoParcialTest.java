package br.com.borurio.web.controller;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.app.EmpresaController;
import br.com.borurio.web.dto.EmpresaAtualizacaoRequest;
import br.com.borurio.web.service.CertSenhaEncryptor;
import br.com.borurio.web.service.EmpresaAtualizacaoService;
import br.com.borurio.web.service.EmpresaCertificadoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Rota B (13-08-2026) — prova, via MockMvc contra o pipeline HTTP/Jackson REAL (só o service é
 * mockado), o comportamento CORRETO de {@code PUT /api/app/empresas/{id}}: atualização parcial
 * explícita, com o payload exatamente como documentado em MTF-001_motor-fiscal-nfe.md §14.3 /
 * ROTEIRO_ENTREGA_TIME_CHINES.md BLOCO 5.
 *
 * Renomeado em 14-08-2026 (era {@code EmpresaControllerAtualizarPayloadParcialDiagnosticoTest}):
 * não é mais um diagnóstico desde 13-08-2026 (banca do achado) — o nome antigo não refletia mais o
 * escopo. Prova o contrato aprovado da Rota B, incluindo a correção de concorrência de 14-08-2026
 * (aqui irrelevante: o service é mockado, a fronteira transacional é coberta em
 * {@code EmpresaAtualizacaoLostUpdateRealMySqlIT}).
 */
@WebMvcTest(EmpresaController.class)
class EmpresaControllerAtualizacaoParcialTest {

    @Autowired MockMvc mockMvc;

    @MockBean EmpresaService empresaService;
    @MockBean CertSenhaEncryptor encryptor;
    @MockBean EmpresaCertificadoService empresaCertificadoService;
    @MockBean EmpresaAtualizacaoService empresaAtualizacaoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private Empresa empresaConsolidada() {
        Empresa e = new Empresa();
        e.setId(1L);
        e.setCnpj("54393421000159");
        e.setRazaoSocial("JCHO GLOBAL LTDA");
        e.setCrt("1");
        e.setUf("SP");
        e.setSerieNfePadrao("1");
        e.setIndFinalPadrao("1");
        e.setAtivo(true);
        e.setControleEstoqueAtivo(false);
        e.setCertPath("/app/certificados/pfx/empresa-X.pfx");
        e.setCertSenha("ENC(valor-sintetico-nunca-deve-aparecer)");
        e.setCertTipo("PKCS12");
        return e;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT com o payload EXATO documentado (só certPath/certSenha/certTipo) -> 200, service "
            + "alcançado com presença correta dos 3 campos, demais campos marcados como ausentes, certSenha nunca no response")
    void atualizar_payloadDocumentadoSoComCertificado_sucesso() throws Exception {
        when(empresaAtualizacaoService.aplicar(eq(1L), any())).thenReturn(empresaConsolidada());

        String payloadDocumentado = """
                {
                  "certPath": "/app/certificados/pfx/empresa-X.pfx",
                  "certSenha": "senha_sintetica_teste",
                  "certTipo": "PKCS12"
                }
                """;

        mockMvc.perform(put("/api/app/empresas/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadDocumentado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.certPath").value("/app/certificados/pfx/empresa-X.pfx"))
                .andExpect(jsonPath("$.data.certTipo").value("PKCS12"))
                .andExpect(jsonPath("$.data.controleEstoqueAtivo").value(false))
                // certSenha nunca aparece no response — nem o valor sintético enviado, nem o
                // criptografado devolvido pelo service mockado.
                .andExpect(jsonPath("$.data.certSenha").doesNotExist());

        ArgumentCaptor<EmpresaAtualizacaoRequest> captor = ArgumentCaptor.forClass(EmpresaAtualizacaoRequest.class);
        verify(empresaAtualizacaoService).aplicar(eq(1L), captor.capture());
        EmpresaAtualizacaoRequest capturado = captor.getValue();

        // Prova real (não presumida) do rastreamento de presença via Jackson end-to-end: os 3
        // campos do payload ficam presentes, todo o resto (incluindo os 8 obrigatórios) fica
        // ausente — é essa distinção que garante que nada além do certificado seja tocado.
        assertTrue(capturado.presente("certPath"));
        assertTrue(capturado.presente("certSenha"));
        assertTrue(capturado.presente("certTipo"));
        assertFalse(capturado.presente("cnpj"));
        assertFalse(capturado.presente("razaoSocial"));
        assertFalse(capturado.presente("crt"));
        assertFalse(capturado.presente("uf"));
        assertFalse(capturado.presente("serieNfePadrao"));
        assertFalse(capturado.presente("indFinalPadrao"));
        assertFalse(capturado.presente("ativo"));
        assertFalse(capturado.presente("controleEstoqueAtivo"));
        assertFalse(capturado.presente("nomeFantasia"));

        verify(empresaCertificadoService).invalidar(1L);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT com corpo vazio {} -> service alcançado, TODOS os campos marcados como ausentes (preserva tudo)")
    void atualizar_corpoVazio_todosOsCamposAusentes() throws Exception {
        when(empresaAtualizacaoService.aplicar(eq(1L), any())).thenReturn(empresaConsolidada());

        mockMvc.perform(put("/api/app/empresas/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EmpresaAtualizacaoRequest> captor = ArgumentCaptor.forClass(EmpresaAtualizacaoRequest.class);
        verify(empresaAtualizacaoService).aplicar(eq(1L), captor.capture());
        EmpresaAtualizacaoRequest capturado = captor.getValue();
        for (String campo : new String[]{"cnpj", "razaoSocial", "crt", "uf", "serieNfePadrao",
                "indFinalPadrao", "ativo", "controleEstoqueAtivo", "nomeFantasia", "ie",
                "certPath", "certSenha", "certTipo"}) {
            assertFalse(capturado.presente(campo), "campo " + campo + " não deveria estar presente com corpo {}");
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT com campo nullable explicitamente null (ex.: {\"nomeFantasia\": null}) -> "
            + "presente=true, valor=null — distinto de omitido, provado via Jackson real")
    void atualizar_campoNullableComNullExplicito_marcaComoPresenteComValorNull() throws Exception {
        when(empresaAtualizacaoService.aplicar(eq(1L), any())).thenReturn(empresaConsolidada());

        mockMvc.perform(put("/api/app/empresas/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nomeFantasia\": null}"))
                .andExpect(status().isOk());

        ArgumentCaptor<EmpresaAtualizacaoRequest> captor = ArgumentCaptor.forClass(EmpresaAtualizacaoRequest.class);
        verify(empresaAtualizacaoService).aplicar(eq(1L), captor.capture());
        EmpresaAtualizacaoRequest capturado = captor.getValue();
        assertTrue(capturado.presente("nomeFantasia"), "null explícito precisa marcar o campo como presente");
        assertNull(capturado.getNomeFantasia());
        assertFalse(capturado.presente("ie"), "campo realmente ausente do JSON não pode ser marcado como presente");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("BusinessException do service (ex.: tentativa de trocar CNPJ) -> 422 com errorCode propagado, resposta nunca 500")
    void atualizar_businessExceptionDoService_propaga422ComErrorCode() throws Exception {
        when(empresaAtualizacaoService.aplicar(eq(1L), any()))
                .thenThrow(BusinessException.cnpjImutavelNaAtualizacao("54393421000159", "11222333000181"));

        mockMvc.perform(put("/api/app/empresas/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\": \"11222333000181\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("EMPRESA_CNPJ_IMUTAVEL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("BusinessException do service (null explícito em campo obrigatório) -> 422 com errorCode propagado")
    void atualizar_nullExplicitoEmObrigatorio_propaga422ComErrorCode() throws Exception {
        when(empresaAtualizacaoService.aplicar(eq(1L), any()))
                .thenThrow(BusinessException.campoObrigatorioNaoPodeSerRemovido("razaoSocial"));

        mockMvc.perform(put("/api/app/empresas/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razaoSocial\": null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("EMPRESA_CAMPO_OBRIGATORIO_NULO"));
    }
}
