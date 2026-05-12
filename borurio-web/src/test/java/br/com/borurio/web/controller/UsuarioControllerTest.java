package br.com.borurio.web.controller;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.config.SecurityConfig;
import br.com.borurio.web.controller.app.UsuarioController;
import br.com.borurio.web.service.UsuarioService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UsuarioController.class)
@Import(SecurityConfig.class)
class UsuarioControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean UsuarioService usuarioService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    @Test
    @WithMockUser(roles = "ADMIN")
    void listar_comAdmin_returnsOk() throws Exception {
        when(usuarioService.listarTodos()).thenReturn(List.of());

        mockMvc.perform(get("/api/app/usuarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @WithMockUser
    void listar_semAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/app/usuarios"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void buscar_existente_returnsOk() throws Exception {
        DbUser user = new DbUser();
        user.setId(1L);
        user.setNome("Admin User");
        user.setEmail("admin@test.com");
        when(usuarioService.buscarPorId(1L)).thenReturn(user);

        mockMvc.perform(get("/api/app/usuarios/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nome").value("Admin User"))
                .andExpect(jsonPath("$.data.email").value("admin@test.com"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void buscar_inexistente_returns404() throws Exception {
        when(usuarioService.buscarPorId(99L)).thenReturn(null);

        mockMvc.perform(get("/api/app/usuarios/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criar_bodyVazio_returns422ComCampos() throws Exception {
        mockMvc.perform(post("/api/app/usuarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.data.nome").exists())
                .andExpect(jsonPath("$.data.email").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void criar_valido_returns200() throws Exception {
        DbUser user = new DbUser();
        user.setId(5L);
        user.setNome("Novo Usuario");
        user.setEmail("novo@test.com");
        user.setRole("OPERADOR");
        when(usuarioService.criar(any())).thenReturn(user);

        String body = """
                {
                  "nome": "Novo Usuario",
                  "email": "novo@test.com",
                  "senha": "senha123"
                }
                """;

        mockMvc.perform(post("/api/app/usuarios")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5))
                .andExpect(jsonPath("$.data.nome").value("Novo Usuario"));
    }

    @Test
    void semToken_returns401() throws Exception {
        mockMvc.perform(get("/api/app/usuarios"))
                .andExpect(status().isUnauthorized());
    }
}
