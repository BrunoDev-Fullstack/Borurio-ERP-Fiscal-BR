package br.com.borurio.web.controller;

import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PingController.class)
@Import(SecurityConfig.class)
class PingControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    @Test
    @WithMockUser
    void ping_autenticado_retornaStatusUp() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.environment").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void ping_semToken_retornaStatusUp() throws Exception {
        mockMvc.perform(get("/api/test/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
