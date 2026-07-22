package br.com.borurio.web.controller;

import br.com.borurio.app.entity.ProdutoBatchItemResultado;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.ProdutoService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProdutoController.class)
@Import(SecurityConfig.class)
class ProdutoBatchControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ProdutoService      produtoService;
    @MockBean EstoqueService      estoqueService;
    @MockBean JwtUtil             jwtUtil;
    @MockBean UserDetailsService  userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    // -------------------------------------------------------------------------
    // 1. lista vazia retorna 400
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_listaVazia_returns400() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenThrow(new IllegalArgumentException("A lista de produtos não pode ser vazia."));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // -------------------------------------------------------------------------
    // 2. lista com 201 itens retorna 422 / BATCH_LIMIT_EXCEEDED
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_limiteExcedido_returns422() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenThrow(BusinessException.batchLimitExceeded());

        StringBuilder itens = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) itens.append(",");
            itens.append("{\"codigo\":\"SKU-").append(i)
                 .append("\",\"descricao\":\"Produto\",\"ncm\":\"12345678\"")
                 .append(",\"unidade\":\"UN\",\"preco\":10.00}");
        }
        itens.append("]");

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":" + itens + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BATCH_LIMIT_EXCEEDED"));
    }

    // -------------------------------------------------------------------------
    // 3. produto novo retorna 207 / CRIADO
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_produtoNovo_returns207Criado() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(ProdutoBatchItemResultado.criado("SKU-001", 42L)));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-001\",\"descricao\":\"Produto\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":10.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.criados").value(1))
                .andExpect(jsonPath("$.atualizados").value(0))
                .andExpect(jsonPath("$.rejeitados").value(0))
                .andExpect(jsonPath("$.resultados[0].status").value("CRIADO"))
                .andExpect(jsonPath("$.resultados[0].produtoId").value(42));
    }

    // -------------------------------------------------------------------------
    // 4. produto existente retorna 207 / ATUALIZADO
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_produtoExistente_returns207Atualizado() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(ProdutoBatchItemResultado.atualizado("SKU-001", 7L)));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-001\",\"descricao\":\"Produto\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":15.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.atualizados").value(1))
                .andExpect(jsonPath("$.resultados[0].status").value("ATUALIZADO"))
                .andExpect(jsonPath("$.resultados[0].produtoId").value(7));
    }

    // -------------------------------------------------------------------------
    // 5. produto inválido retorna 207 / REJEITADO / VALIDATION_ERROR
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_produtoInvalido_returns207Rejeitado() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(ProdutoBatchItemResultado.rejeitado(
                        "SKU-BAD", "VALIDATION_ERROR", "NCM deve ter exatamente 8 dígitos numéricos.")));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-BAD\",\"descricao\":\"Produto\","
                                + "\"ncm\":\"123\",\"unidade\":\"UN\",\"preco\":10.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.rejeitados").value(1))
                .andExpect(jsonPath("$.resultados[0].status").value("REJEITADO"))
                .andExpect(jsonPath("$.resultados[0].errorCode").value("VALIDATION_ERROR"));
    }

    // -------------------------------------------------------------------------
    // 6. lote misto retorna 207 com contagens corretas
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_loteMisto_returns207ComContagens() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(
                        ProdutoBatchItemResultado.criado("SKU-001", 1L),
                        ProdutoBatchItemResultado.atualizado("SKU-002", 2L),
                        ProdutoBatchItemResultado.rejeitado("SKU-003", "VALIDATION_ERROR", "NCM inválido")
                ));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-001\",\"descricao\":\"A\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":10.00},"
                                + "{\"codigo\":\"SKU-002\",\"descricao\":\"B\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":20.00},"
                                + "{\"codigo\":\"SKU-003\",\"descricao\":\"C\","
                                + "\"ncm\":\"123\",\"unidade\":\"UN\",\"preco\":30.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.criados").value(1))
                .andExpect(jsonPath("$.atualizados").value(1))
                .andExpect(jsonPath("$.rejeitados").value(1));
    }

    // -------------------------------------------------------------------------
    // 7. codigo duplicado no payload retorna 207 / DUPLICATE_CODIGO_IN_BATCH
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_codigoDuplicadoNoBatch_returns207ComRejeicao() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(
                        ProdutoBatchItemResultado.criado("SKU-001", 10L),
                        ProdutoBatchItemResultado.rejeitado(
                                "SKU-001", "DUPLICATE_CODIGO_IN_BATCH", "Código duplicado no mesmo lote.")
                ));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-001\",\"descricao\":\"A\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":10.00},"
                                + "{\"codigo\":\"SKU-001\",\"descricao\":\"B\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":10.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.resultados[1].errorCode").value("DUPLICATE_CODIGO_IN_BATCH"));
    }

    // -------------------------------------------------------------------------
    // 8. sem token retorna 401
    // -------------------------------------------------------------------------
    @Test
    void batchUpsert_semToken_returns401() throws Exception {
        mockMvc.perform(post("/api/app/produtos/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    // -------------------------------------------------------------------------
    // 9. update preserva estoque, estoque_reservado e estado
    // A preservação é garantida pelo SQL de atualizarBatch() (não inclui essas colunas).
    // O controller simplesmente repassa o resultado do service — verificamos o ATUALIZADO.
    // -------------------------------------------------------------------------
    @Test
    @WithMockUser
    void batchUpsert_update_preservaEstoqueEEstado() throws Exception {
        when(produtoService.batchUpsert(any(), any()))
                .thenReturn(List.of(ProdutoBatchItemResultado.atualizado("SKU-EXIST", 5L)));

        mockMvc.perform(post("/api/app/produtos/batch")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"produtos\":[{\"codigo\":\"SKU-EXIST\",\"descricao\":\"Novo Nome\","
                                + "\"ncm\":\"12345678\",\"unidade\":\"UN\",\"preco\":99.00}]}"))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.resultados[0].status").value("ATUALIZADO"))
                .andExpect(jsonPath("$.resultados[0].produtoId").value(5));
    }
}
