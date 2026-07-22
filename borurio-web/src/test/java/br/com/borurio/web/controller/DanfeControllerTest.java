package br.com.borurio.web.controller;

import br.com.borurio.fiscal.danfe.DanfeService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.fiscal.DanfeController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.NoSuchElementException;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DanfeController.class)
class DanfeControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean DanfeService  danfeService;
    @MockBean JwtUtil       jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private static final String CHAVE_44 = "35260500000000000000550010000000011234567890";

    @Test
    @WithMockUser
    void getDanfe_nfeExistente_retornaPdf() throws Exception {
        byte[] fakePdf = new byte[]{0x25, 0x50, 0x44, 0x46}; // %PDF magic bytes
        when(danfeService.gerarDanfe(CHAVE_44)).thenReturn(fakePdf);

        mockMvc.perform(get("/api/fiscal/nfe/{chave}/danfe", CHAVE_44))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(fakePdf));
    }

    @Test
    @WithMockUser
    void getDanfe_nfeNaoEncontrada_retorna404() throws Exception {
        when(danfeService.gerarDanfe(CHAVE_44))
                .thenThrow(new NoSuchElementException("NF-e não encontrada"));

        mockMvc.perform(get("/api/fiscal/nfe/{chave}/danfe", CHAVE_44))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void getDanfe_xmlIndisponivel_retorna422() throws Exception {
        when(danfeService.gerarDanfe(CHAVE_44))
                .thenThrow(new IllegalStateException("XML não disponível"));

        mockMvc.perform(get("/api/fiscal/nfe/{chave}/danfe", CHAVE_44))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @WithMockUser
    void getDanfe_chaveCurta_retorna400() throws Exception {
        mockMvc.perform(get("/api/fiscal/nfe/{chave}/danfe", "123"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/fiscal/nfe/{chave}/danfe", CHAVE_44))
                .andExpect(status().isUnauthorized());
    }
}
