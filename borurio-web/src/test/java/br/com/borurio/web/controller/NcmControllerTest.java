package br.com.borurio.web.controller;

import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.Ncm;
import br.com.borurio.fiscal.service.NcmService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.fiscal.NcmController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NcmController.class)
class NcmControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NcmService ncmService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    @Test
    @WithMockUser
    void listarTodos_retornaListaNcm() throws Exception {
        Ncm ncm = Ncm.builder().id(1L).codigo("01011010").descricao("Animais vivos").build();
        when(ncmService.listarTodos()).thenReturn(List.of(ncm));

        mockMvc.perform(get("/api/fiscal/ncm/listar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser
    void buscarPorCodigo_encontrado_retornaNCM() throws Exception {
        Ncm ncm = Ncm.builder().id(1L).codigo("01011010").descricao("Animais vivos").build();
        when(ncmService.buscarPorCodigo("01011010")).thenReturn(ncm);

        mockMvc.perform(get("/api/fiscal/ncm/01011010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.codigo").value("01011010"));
    }

    @Test
    @WithMockUser
    void buscarPorCodigo_naoEncontrado_retornaErro500() throws Exception {
        when(ncmService.buscarPorCodigo("99999999")).thenReturn(null);

        mockMvc.perform(get("/api/fiscal/ncm/99999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    @WithMockUser
    void sincronizar_retornaSucesso() throws Exception {
        when(ncmService.sincronizarTabela())
                .thenAnswer(inv -> ResultUtil.success("Sincronizacao realizada"));

        mockMvc.perform(post("/api/fiscal/ncm/sincronizar").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/fiscal/ncm/listar"))
                .andExpect(status().isUnauthorized());
    }
}
