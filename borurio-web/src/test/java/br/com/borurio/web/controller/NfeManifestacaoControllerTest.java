package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeManifestacaoService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.fiscal.NfeManifestacaoController;
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

@WebMvcTest(NfeManifestacaoController.class)
class NfeManifestacaoControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeManifestacaoService nfeManifestacaoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    private static final String CHAVE = "35260554393421000159550010000000351199116560";
    private static final String CNPJ  = "54393421000159";

    private String body(String tipo, String xJust) {
        String just = xJust != null
                ? ",\"xJust\":\"" + xJust + "\""
                : "";
        return "{\"chaveNfe\":\"" + CHAVE + "\"" +
               ",\"tipoEvento\":\"" + tipo + "\"" +
               ",\"cnpjDestinatario\":\"" + CNPJ + "\"" +
               just + "}";
    }

    @Test
    @WithMockUser
    void manifestar_210200_ciencia_sucesso() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210200", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("135 - Evento registrado e vinculado a NF-e"));
    }

    @Test
    @WithMockUser
    void manifestar_210210_confirmacao_sucesso() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210210", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void manifestar_210220_desconhecimento_sucesso() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210220", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void manifestar_210240_operacaoNaoRealizada_comJust_sucesso() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenReturn("135 - Evento registrado e vinculado a NF-e");

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210240", "Mercadoria nao recebida pelo destinatario conforme acordado")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void manifestar_210240_semJust_retornaErro() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenThrow(new IllegalArgumentException(
                        "xJust obrigatório para 210240 e deve ter no mínimo 15 caracteres."));

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210240", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @WithMockUser
    void manifestar_tipoInvalido_retornaErro() throws Exception {
        when(nfeManifestacaoService.manifestar(any()))
                .thenThrow(new IllegalArgumentException(
                        "Tipo de evento inválido. Valores aceitos: 210200, 210210, 210220, 210240."));

        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("999999", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/manifestar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("210200", null)))
                .andExpect(status().isUnauthorized());
    }
}
