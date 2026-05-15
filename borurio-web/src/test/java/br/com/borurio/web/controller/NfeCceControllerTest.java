package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.fiscal.NfeCceController;
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

@WebMvcTest(NfeCceController.class)
class NfeCceControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeCceService nfeCceService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    private static final String BODY_VALIDO = """
            {
              "chaveNfe": "35240454393421000159550010000000011000000017",
              "correcao": "Correcao do campo destinatario para testes automatizados ERP",
              "sequencia": 1
            }
            """;

    @Test
    @WithMockUser
    void corrigir_sucesso_retornaCodigo200() throws Exception {
        when(nfeCceService.corrigir(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("135 - Evento registrado e vinculado a NF-e"));
    }

    @Test
    @WithMockUser
    void corrigir_dadosInvalidos_retornaErro500() throws Exception {
        when(nfeCceService.corrigir(any()))
                .thenThrow(new IllegalArgumentException("Correção deve ter no mínimo 15 caracteres"));

        mockMvc.perform(post("/api/fiscal/nfe/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());
    }
}
