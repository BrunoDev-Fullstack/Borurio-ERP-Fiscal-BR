package br.com.borurio.web.controller;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.app.PedidoController;
import br.com.borurio.web.service.PedidoEmissaoService;
import br.com.borurio.web.service.PedidoOperacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PedidoController.class)
class PedidoControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PedidoService pedidoService;
    @MockBean PedidoEmissaoService pedidoEmissaoService;
    @MockBean PedidoOperacaoService pedidoOperacaoService;
    @MockBean EmitenteProperties emitente;
    @MockBean EmpresaService empresaService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    @Test
    @WithMockUser
    void listar_returnsPaginado() throws Exception {
        when(pedidoService.listarPaginado(isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/app/pedidos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser
    void criar_bodyVazio_returns422ComCampos() throws Exception {
        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.destCnpjCpf").exists())
                .andExpect(jsonPath("$.data.destRazaoSocial").exists());
    }

    @Test
    @WithMockUser
    void criar_valido_returns200() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");

        Pedido pedido = new Pedido();
        pedido.setId(1L);
        pedido.setDestCnpjCpf("12345678000195");
        pedido.setDestRazaoSocial("Cliente Teste");
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        String body = """
                {
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Teste",
                  "destUf": "SP"
                }
                """;

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("RASCUNHO"));
    }

    @Test
    @WithMockUser
    void buscar_inexistente_returns404() throws Exception {
        when(pedidoService.buscarComItens(99L))
                .thenThrow(new NoSuchElementException("Pedido não encontrado"));

        mockMvc.perform(get("/api/app/pedidos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser
    void emitir_retornaChaveNfe() throws Exception {
        NfeGeracaoResult result = new NfeGeracaoResult(
                "12345678901234567890123456789012345678901234",
                "<retEnviNFe/>");
        when(pedidoEmissaoService.emitir(1L)).thenReturn(result);

        mockMvc.perform(post("/api/app/pedidos/1/emitir")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chaveNfe").value("12345678901234567890123456789012345678901234"));
    }

    @Test
    @WithMockUser
    void jsonMalformado_returns400() throws Exception {
        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void semToken_returns401() throws Exception {
        mockMvc.perform(get("/api/app/pedidos"))
                .andExpect(status().isUnauthorized());
    }
}
