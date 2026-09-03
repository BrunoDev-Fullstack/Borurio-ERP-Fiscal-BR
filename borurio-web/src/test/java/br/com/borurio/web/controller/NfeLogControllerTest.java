package br.com.borurio.web.controller;

import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.fiscal.NfeLogController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NfeLogController.class)
class NfeLogControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeLogService nfeLogService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    @Test
    @WithMockUser
    void listarTodos_retornaPaginado() throws Exception {
        when(nfeLogService.listarPaginado(isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/fiscal/nfe/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser
    void buscarPorChave_logEncontrado_retornaLista() throws Exception {
        NfeLog log = new NfeLog();
        when(nfeLogService.buscarPorChave("35240454393421000159550010000000011000000017"))
                .thenReturn(List.of(log));

        mockMvc.perform(get("/api/fiscal/nfe/logs/35240454393421000159550010000000011000000017"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser
    void buscarPorChave_naoEncontrado_retorna404() throws Exception {
        when(nfeLogService.buscarPorChave("0000")).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/fiscal/nfe/logs/0000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/fiscal/nfe/logs"))
                .andExpect(status().isUnauthorized());
    }
}
