package br.com.borurio.web.controller;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.ModalidadeFrete;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeTransmitService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.config.SecurityConfig;
import br.com.borurio.web.controller.fiscal.NfeEnvioController;
import br.com.borurio.web.service.NfeGeracaoService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Verifica que os endpoints legados do NfeEnvioController (A-03) exigem ADMIN.
 * OPERADOR e usuário anônimo não devem ter acesso.
 *
 * @Import(SecurityConfig.class) é obrigatório: @WebMvcTest não carrega @Configuration
 * customizado por padrão, então @EnableMethodSecurity e a desabilitação de CSRF
 * do SecurityConfig não seriam aplicados sem este import.
 */
@WebMvcTest(NfeEnvioController.class)
@Import(SecurityConfig.class)
class NfeEnvioControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean NfeOrquestradorService nfeOrquestradorService;
    @MockBean NfeTransmitService     nfeTransmitService;
    @MockBean NfeGeracaoService      nfeGeracaoService;
    @MockBean EmitenteProperties     emitente;
    @MockBean JwtUtil                jwtUtil;
    @MockBean UserDetailsService     userDetailsService;
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

    private static final String CHAVE_44  = "35240454393421000159550010000000011000000017";
    private static final String BODY_XML  = "<nfeProc/>";
    private static final String BODY_JSON = """
            {
              "destCnpjCpf": "12345678000195",
              "destRazaoSocial": "Empresa Teste",
              "destUf": "SP",
              "itens": []
            }
            """;

    // =========================================================================
    // POST /api/fiscal/nfe/gerar
    // =========================================================================

    @Nested
    class Gerar {

        @Test
        void semAutenticacao_retorna401() throws Exception {
            mockMvc.perform(post("/api/fiscal/nfe/gerar")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_JSON))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        void semRoleAdmin_retorna403() throws Exception {
            mockMvc.perform(post("/api/fiscal/nfe/gerar")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_JSON))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void comAdmin_chegaAoServico() throws Exception {
            doThrow(new IllegalArgumentException("dados inválidos"))
                    .when(nfeGeracaoService).gerar(any(NfeEmissaoRequest.class), isNull(), eq(ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE));

            mockMvc.perform(post("/api/fiscal/nfe/gerar")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_JSON))
                    .andExpect(status().isOk());
        }

        /**
         * Endpoint legado, sem vínculo confirmado com o fluxo de marketplace — deve preservar
         * o comportamento anterior (modFrete=9), nunca herdar a regra do fluxo OMS.
         */
        @Test
        @WithMockUser(roles = "ADMIN")
        void comAdmin_declaraModalidadeFreteSemTransporte() throws Exception {
            when(nfeGeracaoService.gerar(any(NfeEmissaoRequest.class), isNull(), eq(ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE)))
                    .thenReturn(new br.com.borurio.fiscal.dto.NfeGeracaoResult("chave123", "<soap/>"));

            mockMvc.perform(post("/api/fiscal/nfe/gerar")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY_JSON))
                    .andExpect(status().isOk());

            verify(nfeGeracaoService).gerar(any(NfeEmissaoRequest.class), isNull(), eq(ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE));
        }
    }

    // =========================================================================
    // POST /api/fiscal/nfe/enviar
    // =========================================================================

    @Nested
    class Enviar {

        @Test
        void semAutenticacao_retorna401() throws Exception {
            mockMvc.perform(post("/api/fiscal/nfe/enviar")
                            .header("CNPJ-Emitente", "54393421000159")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(BODY_XML))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        void semRoleAdmin_retorna403() throws Exception {
            mockMvc.perform(post("/api/fiscal/nfe/enviar")
                            .with(csrf())
                            .header("CNPJ-Emitente", "54393421000159")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(BODY_XML))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void comAdmin_chegaAoServico() throws Exception {
            when(nfeOrquestradorService.processar(anyString(), anyString()))
                    .thenReturn("100 - Autorizado");

            mockMvc.perform(post("/api/fiscal/nfe/enviar")
                            .with(csrf())
                            .header("CNPJ-Emitente", "54393421000159")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content(BODY_XML))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // GET /api/fiscal/nfe/status
    // =========================================================================

    @Nested
    class Status {

        @Test
        void semAutenticacao_retorna401() throws Exception {
            mockMvc.perform(get("/api/fiscal/nfe/status"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        void semRoleAdmin_retorna403() throws Exception {
            mockMvc.perform(get("/api/fiscal/nfe/status"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void comAdmin_chegaAoServico() throws Exception {
            when(emitente.getUf()).thenReturn("SP");
            when(nfeTransmitService.consultarStatus(anyString(), anyInt()))
                    .thenReturn("107 - Servico em Operacao");

            mockMvc.perform(get("/api/fiscal/nfe/status"))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // GET /api/fiscal/nfe/{chave}
    // =========================================================================

    @Nested
    class ConsultarChave {

        @Test
        void semAutenticacao_retorna401() throws Exception {
            mockMvc.perform(get("/api/fiscal/nfe/" + CHAVE_44))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @WithMockUser
        void semRoleAdmin_retorna403() throws Exception {
            mockMvc.perform(get("/api/fiscal/nfe/" + CHAVE_44))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = "ADMIN")
        void comAdmin_chegaAoServico() throws Exception {
            when(emitente.getUf()).thenReturn("SP");
            when(nfeTransmitService.consultarNfe(anyString(), anyString(), anyInt()))
                    .thenReturn("100 - Autorizado");

            mockMvc.perform(get("/api/fiscal/nfe/" + CHAVE_44))
                    .andExpect(status().isOk());
        }
    }
}
