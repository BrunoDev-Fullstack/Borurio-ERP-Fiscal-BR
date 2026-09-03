package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.fiscal.NfeCceController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Gate CC-e (12-08-2026) — o endpoint cru /api/fiscal/nfe/cce foi desabilitado: nunca teve
 * contexto de pedido, não participa do gate de sequência/idempotência que passa a proteger toda
 * transmissão de 110110. Prova que a rota nunca chama NfeCceService (nunca toca SEFAZ) e sempre
 * devolve HTTP 410 explícito — nunca 404 silencioso, nunca sucesso.
 */
@WebMvcTest(NfeCceController.class)
class NfeCceControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeCceService nfeCceService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private static final String BODY_VALIDO = """
            {
              "chaveNfe": "35240454393421000159550010000000011000000017",
              "correcao": "Correcao do campo destinatario para testes automatizados ERP"
            }
            """;

    @Test
    @WithMockUser
    void corrigir_endpointDesabilitado_retorna410SemChamarServico() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(410))
                .andExpect(jsonPath("$.errorCode").value("CCE_ENDPOINT_LEGADO_DESABILITADO"));

        verifyNoInteractions(nfeCceService);
    }

    @Test
    void semToken_retorna401_semChamarServico() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(nfeCceService);
    }
}
