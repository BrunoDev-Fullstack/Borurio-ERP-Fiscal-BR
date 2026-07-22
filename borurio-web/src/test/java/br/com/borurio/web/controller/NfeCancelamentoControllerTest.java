package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeCancelamentoService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.fiscal.NfeCancelamentoController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NfeCancelamentoController.class)
class NfeCancelamentoControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeCancelamentoService nfeCancelamentoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private static final String BODY_VALIDO = """
            {
              "chaveNfe": "35240454393421000159550010000000011000000017",
              "nProtocolo": "135240000000001",
              "justificativa": "Cancelamento solicitado para testes automatizados do ERP"
            }
            """;

    @Test
    @WithMockUser
    void cancelar_sucesso_retornaCodigo200() throws Exception {
        when(nfeCancelamentoService.cancelar(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("135 - Evento registrado e vinculado a NF-e"));
    }

    @Test
    @WithMockUser
    void cancelar_dadosInvalidos_retornaErro500() throws Exception {
        when(nfeCancelamentoService.cancelar(any()))
                .thenThrow(new IllegalArgumentException("Justificativa deve ter no mínimo 15 caracteres"));

        mockMvc.perform(post("/api/fiscal/nfe/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());
    }
}
