package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeInutilizacaoService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.fiscal.NfeInutilizacaoController;
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

@WebMvcTest(NfeInutilizacaoController.class)
class NfeInutilizacaoControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeInutilizacaoService nfeInutilizacaoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    private static final String BODY_VALIDO = """
            {
              "ano": "24",
              "serie": "1",
              "nNFIni": "100",
              "nNFFin": "105",
              "justificativa": "Inutilizacao de faixa numerica para testes automatizados ERP"
            }
            """;

    @Test
    @WithMockUser
    void inutilizar_sucesso_retornaCodigo200() throws Exception {
        when(nfeInutilizacaoService.inutilizar(any()))
                .thenReturn("102 - Inutilização de número homologado");

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("102 - Inutilização de número homologado"));
    }

    @Test
    @WithMockUser
    void inutilizar_dadosInvalidos_retornaErro500() throws Exception {
        when(nfeInutilizacaoService.inutilizar(any()))
                .thenThrow(new IllegalArgumentException("Série inválida para inutilização"));

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());
    }
}
