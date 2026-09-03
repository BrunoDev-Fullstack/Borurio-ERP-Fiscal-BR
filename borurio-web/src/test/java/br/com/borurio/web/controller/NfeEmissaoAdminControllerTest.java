package br.com.borurio.web.controller;

import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.admin.NfeEmissaoAdminController;
import br.com.borurio.web.dto.AbandonoCicloResultado;
import br.com.borurio.web.service.NfeEmissaoService;
import br.com.borurio.fiscal.entity.NfeEmissao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NfeEmissaoAdminController.class)
class NfeEmissaoAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeEmissaoService nfeEmissaoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private static final String URL = "/api/admin/nfe-emissoes/2/abandonar";
    private static final String BODY_VALIDO = """
            { "motivo": "descricao chinesa incorrigivel no pedido 67 (xProd fora do charset NF-e)" }
            """;

    private static NfeEmissao emissao2() {
        NfeEmissao e = new NfeEmissao();
        e.setId(2L);
        e.setPedidoId(67L);
        e.setCnpjEmitente("22418179000134");
        e.setSerie("5");
        e.setNumeroNfe(1);
        e.setEstado(NfeEmissao.Estados.AGUARDANDO_CORRECAO);
        e.setCstat(225);
        e.setXmotivo("Rejeição: Falha no Schema XML do lote de NFe");
        return e;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void abandonar_sucesso_retorna200_estadoAbandonado_sequenciaAvancada() throws Exception {
        when(nfeEmissaoService.abandonarCiclo(eq(2L), any()))
                .thenReturn(AbandonoCicloResultado.abandonado(emissao2(), true, 1, true));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estadoAnterior").value("AGUARDANDO_CORRECAO"))
                .andExpect(jsonPath("$.data.estadoAtual").value("ABANDONADO"))
                .andExpect(jsonPath("$.data.numeroNfe").value(1))
                .andExpect(jsonPath("$.data.serie").value("5"))
                .andExpect(jsonPath("$.data.gateLiberado").value(true))
                .andExpect(jsonPath("$.data.ultimoNumeroResultante").value(1))
                .andExpect(jsonPath("$.data.sequenciaAvancada").value(true))
                .andExpect(jsonPath("$.data.idempotente").value(false));

        verify(nfeEmissaoService).abandonarCiclo(eq(2L), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void abandonar_idempotente_retorna200_comFlag() throws Exception {
        NfeEmissao jaAbandonado = emissao2();
        jaAbandonado.setEstado(NfeEmissao.Estados.ABANDONADO);
        when(nfeEmissaoService.abandonarCiclo(eq(2L), any()))
                .thenReturn(AbandonoCicloResultado.idempotente(jaAbandonado, true, 1, false));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.idempotente").value(true))
                .andExpect(jsonPath("$.data.ultimoNumeroResultante").value(1))
                .andExpect(jsonPath("$.data.sequenciaAvancada").value(false))
                .andExpect(jsonPath("$.data.estadoAtual").value("ABANDONADO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void abandonar_motivoCurto_retorna422_semChamarServico() throws Exception {
        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"motivo\": \"curto\" }"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.motivo").exists());

        verify(nfeEmissaoService, never()).abandonarCiclo(anyLong(), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void abandonar_estadoNaoAbandonavel_propaga422ComErrorCode() throws Exception {
        when(nfeEmissaoService.abandonarCiclo(eq(2L), any()))
                .thenThrow(BusinessException.cicloNaoAbandonavel(2L, "AUTORIZADO"));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CICLO_NAO_ABANDONAVEL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void abandonar_emissaoInexistente_propaga404() throws Exception {
        when(nfeEmissaoService.abandonarCiclo(eq(2L), any()))
                .thenThrow(BusinessException.emissaoNaoEncontrada(2L));

        mockMvc.perform(post(URL).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_VALIDO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("EMISSAO_NOT_FOUND"));
    }

    /**
     * Guarda de acesso: o endpoint é exclusivamente administrativo. A proteção real é dupla —
     * o matcher {@code /api/admin/**} em SecurityConfig e o {@code @PreAuthorize} da classe.
     * Verificado por reflexão (determinístico, sem depender de qual SecurityFilterChain o
     * {@code @WebMvcTest} carrega — mesmo motivo pelo qual OmsAuthorizationAdminController também
     * não faz esse teste no slice web).
     */
    @Test
    void endpoint_eExclusivamenteAdministrativo() {
        RequestMapping rm = NfeEmissaoAdminController.class.getAnnotation(RequestMapping.class);
        assertTrue(rm != null && rm.value().length == 1 && rm.value()[0].startsWith("/api/admin/"),
                "controller deve estar mapeado sob /api/admin/**");

        PreAuthorize pa = NfeEmissaoAdminController.class.getAnnotation(PreAuthorize.class);
        assertEquals("hasRole('ADMIN')", pa == null ? null : pa.value(),
                "controller deve exigir ROLE_ADMIN via @PreAuthorize");
    }

    // --- /marcar-transporte-nao-entregue ---

    private static final String URL_TNE = "/api/admin/nfe-emissoes/3/marcar-transporte-nao-entregue";
    private static final String BODY_TNE = """
            { "motivo": "gateway 403 - lote nao chegou ao autorizador da SEFAZ (cert cliente rejeitado)" }
            """;

    private static br.com.borurio.fiscal.entity.NfeEmissao emissao3() {
        br.com.borurio.fiscal.entity.NfeEmissao e = new br.com.borurio.fiscal.entity.NfeEmissao();
        e.setId(3L);
        e.setPedidoId(68L);
        e.setCnpjEmitente("54393421000159");
        e.setSerie("1");
        e.setNumeroNfe(49);
        e.setEstado(br.com.borurio.fiscal.entity.NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        e.setCstat(-1);
        e.setXmotivo("Erro ao interpretar resposta: DOCTYPE is disallowed ...");
        e.setChaveNfe("35260954393421000159550010000000491699768389");
        return e;
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void transporteNaoEntregue_sucesso_retorna200_estadoNovo_sequenciaAvancada_pedidoParaErro() throws Exception {
        when(nfeEmissaoService.marcarTransporteNaoEntregue(eq(3L), any()))
                .thenReturn(br.com.borurio.web.dto.TransporteNaoEntregueResultado.aplicado(emissao3(), true, 49, true, false));

        mockMvc.perform(post(URL_TNE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_TNE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.estadoAnterior").value("PENDENTE_CONFIRMACAO"))
                .andExpect(jsonPath("$.data.estadoAtual").value("TRANSPORTE_NAO_ENTREGUE"))
                .andExpect(jsonPath("$.data.numeroNfe").value(49))
                .andExpect(jsonPath("$.data.gateLiberado").value(true))
                .andExpect(jsonPath("$.data.ultimoNumeroResultante").value(49))
                .andExpect(jsonPath("$.data.sequenciaAvancada").value(true))
                .andExpect(jsonPath("$.data.pedidoParaErro").value(true))
                .andExpect(jsonPath("$.data.idempotente").value(false))
                .andExpect(jsonPath("$.data.chaveNfePreservada")
                        .value("35260954393421000159550010000000491699768389"));

        verify(nfeEmissaoService).marcarTransporteNaoEntregue(eq(3L), any());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void transporteNaoEntregue_evidenciaDeProcessamento_propaga422() throws Exception {
        when(nfeEmissaoService.marcarTransporteNaoEntregue(eq(3L), any()))
                .thenThrow(BusinessException.evidenciaDeProcessamento(3L, "35260954393421000159550010000000491699768389"));

        mockMvc.perform(post(URL_TNE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_TNE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("EVIDENCIA_DE_PROCESSAMENTO"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void transporteNaoEntregue_estadoNaoElegivel_propaga422() throws Exception {
        when(nfeEmissaoService.marcarTransporteNaoEntregue(eq(3L), any()))
                .thenThrow(BusinessException.cicloNaoElegivelTransporte(3L, "AUTORIZADO"));

        mockMvc.perform(post(URL_TNE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY_TNE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CICLO_NAO_ELEGIVEL_TRANSPORTE"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void transporteNaoEntregue_motivoCurto_retorna422_semChamarServico() throws Exception {
        mockMvc.perform(post(URL_TNE).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"motivo\": \"curto\" }"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.motivo").exists());

        verify(nfeEmissaoService, never()).marcarTransporteNaoEntregue(anyLong(), any());
    }
}
