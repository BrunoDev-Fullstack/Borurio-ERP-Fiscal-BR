package br.com.borurio.web.controller;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.controller.app.EmpresaController;
import br.com.borurio.web.service.CertSenhaEncryptor;
import br.com.borurio.web.service.EmpresaCertificadoService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EmpresaController.class)
class EmpresaControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean EmpresaService empresaService;
    @MockBean CertSenhaEncryptor encryptor;
    @MockBean EmpresaCertificadoService empresaCertificadoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private Empresa empresaStub() {
        Empresa e = new Empresa();
        e.setId(1L);
        e.setCnpj("54393421000159");
        e.setRazaoSocial("JCHO GLOBAL LTDA");
        return e;
    }

    @Test
    @WithMockUser
    void listar_retornaLista() throws Exception {
        when(empresaService.listarTodas()).thenReturn(List.of(empresaStub()));

        mockMvc.perform(get("/api/app/empresas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @WithMockUser
    void buscarPorId_encontrado_retornaEmpresa() throws Exception {
        when(empresaService.buscarPorId(1L)).thenReturn(empresaStub());

        mockMvc.perform(get("/api/app/empresas/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cnpj").value("54393421000159"));
    }

    @Test
    @WithMockUser
    void buscarPorId_inexistente_retorna404() throws Exception {
        when(empresaService.buscarPorId(99L))
                .thenThrow(new NoSuchElementException("Empresa não encontrada"));

        mockMvc.perform(get("/api/app/empresas/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @WithMockUser
    void salvar_retornaEmpresaCriada() throws Exception {
        when(empresaService.salvar(any())).thenReturn(empresaStub());

        mockMvc.perform(post("/api/app/empresas")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"54393421000159\",\"razaoSocial\":\"JCHO GLOBAL LTDA\",\"crt\":\"1\",\"uf\":\"SP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.cnpj").value("54393421000159"));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(get("/api/app/empresas"))
                .andExpect(status().isUnauthorized());
    }
}
