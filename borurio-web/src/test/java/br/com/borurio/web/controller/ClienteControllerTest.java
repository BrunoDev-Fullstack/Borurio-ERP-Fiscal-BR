package br.com.borurio.web.controller;

import br.com.borurio.app.entity.Cliente;
import br.com.borurio.app.service.ClienteService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.app.ClienteController;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ClienteController.class)
class ClienteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean ClienteService clienteService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    @Test
    @WithMockUser
    void listar_returnsOkComContent() throws Exception {
        when(clienteService.listarTodos()).thenReturn(List.of());

        mockMvc.perform(get("/api/app/clientes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser
    void buscar_clienteExistente_returnsOk() throws Exception {
        Cliente c = new Cliente();
        c.setId(1L);
        c.setTipoPessoa("PJ");
        c.setRazaoSocial("Empresa Teste");
        when(clienteService.buscarPorId(1L)).thenReturn(c);

        mockMvc.perform(get("/api/app/clientes/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tipoPessoa").value("PJ"))
                .andExpect(jsonPath("$.data.razaoSocial").value("Empresa Teste"));
    }

    @Test
    @WithMockUser
    void buscar_clienteInexistente_returns404() throws Exception {
        when(clienteService.buscarPorId(99L)).thenReturn(null);

        mockMvc.perform(get("/api/app/clientes/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser
    void criar_bodyVazio_returns422ComCampos() throws Exception {
        mockMvc.perform(post("/api/app/clientes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.tipoPessoa").exists())
                .andExpect(jsonPath("$.data.razaoSocial").exists());
    }

    @Test
    @WithMockUser
    void criar_tipoPessoaInvalido_returns422() throws Exception {
        String body = """
                {"tipoPessoa":"XX","razaoSocial":"Teste"}
                """;

        mockMvc.perform(post("/api/app/clientes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.tipoPessoa").exists());
    }

    @Test
    @WithMockUser
    void criar_valido_returns200() throws Exception {
        Cliente c = new Cliente();
        c.setId(5L);
        c.setTipoPessoa("PF");
        c.setRazaoSocial("Joao Silva");
        when(clienteService.salvar(any())).thenReturn(c);

        String body = """
                {"tipoPessoa":"PF","razaoSocial":"Joao Silva"}
                """;

        mockMvc.perform(post("/api/app/clientes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.tipoPessoa").value("PF"));
    }

    @Test
    @WithMockUser
    void jsonMalformado_returns400() throws Exception {
        mockMvc.perform(post("/api/app/clientes")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void semToken_returns401() throws Exception {
        mockMvc.perform(get("/api/app/clientes"))
                .andExpect(status().isUnauthorized());
    }
}
