package br.com.borurio.web.controller;

import br.com.borurio.app.entity.EstoqueMovimento;
import br.com.borurio.app.entity.EstoqueSaldo;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.ProdutoService;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.config.SecurityConfig;
import br.com.borurio.web.controller.app.ProdutoController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProdutoController.class)
@Import(SecurityConfig.class)
class ProdutoControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ProdutoService produtoService;
    @MockBean EstoqueService estoqueService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    @Test
    @WithMockUser
    void listar_returnsPaginado() throws Exception {
        when(produtoService.listarPaginado(isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/app/produtos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser
    void buscar_existente_returnsOk() throws Exception {
        Produto p = new Produto();
        p.setId(1L);
        p.setCodigo("PROD-01");
        p.setDescricao("Produto Teste");
        when(produtoService.buscarPorId(1L)).thenReturn(p);

        mockMvc.perform(get("/api/app/produtos/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.codigo").value("PROD-01"));
    }

    @Test
    @WithMockUser
    void buscar_inexistente_returns404() throws Exception {
        when(produtoService.buscarPorId(99L))
                .thenThrow(new NoSuchElementException("Produto não encontrado"));

        mockMvc.perform(get("/api/app/produtos/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser
    void salvar_bodyVazio_returns422ComCampos() throws Exception {
        mockMvc.perform(post("/api/app/produtos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.codigo").exists())
                .andExpect(jsonPath("$.data.descricao").exists());
    }

    @Test
    @WithMockUser
    void salvar_valido_returns200() throws Exception {
        Produto p = new Produto();
        p.setId(5L);
        p.setCodigo("PROD-05");
        p.setDescricao("Produto Válido");
        when(produtoService.salvar(any())).thenReturn(p);

        String body = """
                {
                  "codigo": "PROD-05",
                  "descricao": "Produto Válido",
                  "ncm": "12345678",
                  "cfop": "5102",
                  "unidade": "UN",
                  "preco": 10.00,
                  "origem": 0
                }
                """;

        mockMvc.perform(post("/api/app/produtos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.codigo").value("PROD-05"));
    }

    @Test
    @WithMockUser
    void jsonMalformado_returns400() throws Exception {
        mockMvc.perform(post("/api/app/produtos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void semToken_returns401() throws Exception {
        mockMvc.perform(get("/api/app/produtos"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    void consultarEstoque_existente_returnsSaldo() throws Exception {
        EstoqueSaldo saldo = new EstoqueSaldo();
        saldo.setProdutoId(1L);
        saldo.setEstoqueTotal(new BigDecimal("100.00"));
        saldo.setEstoqueReservado(new BigDecimal("10.00"));
        saldo.setEstoqueDisponivel(new BigDecimal("90.00"));
        when(estoqueService.consultarSaldo(anyLong(), isNull())).thenReturn(saldo);

        mockMvc.perform(get("/api/app/produtos/1/estoque"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.estoqueTotal").value(100.00))
                .andExpect(jsonPath("$.data.estoqueDisponivel").value(90.00));
    }

    @Test
    @WithMockUser
    void consultarEstoque_produtoInexistente_returns404() throws Exception {
        when(estoqueService.consultarSaldo(anyLong(), isNull()))
                .thenThrow(new NoSuchElementException("Produto não encontrado"));

        mockMvc.perform(get("/api/app/produtos/99/estoque"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void entrada_quantidadeValida_returns200() throws Exception {
        EstoqueMovimento mov = new EstoqueMovimento();
        mov.setId(1L);
        mov.setTipo("ENTRADA");
        mov.setQuantidade(new BigDecimal("50.00"));
        when(estoqueService.entrada(anyLong(), isNull(), any(), anyString(), any())).thenReturn(mov);

        String body = """
                {"quantidade": 50.00, "observacao": "Ajuste inicial"}
                """;

        mockMvc.perform(post("/api/app/produtos/1/estoque/entrada")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tipo").value("ENTRADA"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void entrada_quantidadeAusente_returns422() throws Exception {
        mockMvc.perform(post("/api/app/produtos/1/estoque/entrada")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    @WithMockUser
    void entrada_semAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/app/produtos/1/estoque/entrada")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantidade\": 50.00, \"observacao\": \"teste\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
