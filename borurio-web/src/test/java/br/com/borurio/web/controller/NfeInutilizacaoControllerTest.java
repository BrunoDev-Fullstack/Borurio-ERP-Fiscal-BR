package br.com.borurio.web.controller;

import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.fiscal.service.NfeInutilizacaoService;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.fiscal.NfeInutilizacaoController;
import br.com.borurio.web.service.EmpresaCertificadoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.fiscal.service.CertificadoContexto;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NfeInutilizacaoController.class)
class NfeInutilizacaoControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean NfeInutilizacaoService nfeInutilizacaoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;
    @MockBean EmpresaMapper empresaMapper;
    @MockBean EmpresaCertificadoService empresaCertificadoService;

    private static final String BODY_VALIDO = """
            {
              "ano": "24",
              "serie": "1",
              "nNFIni": "100",
              "nNFFin": "105",
              "justificativa": "Inutilizacao de faixa numerica para testes automatizados ERP"
            }
            """;

    @Test
    @WithMockUser
    void inutilizar_sucesso_retornaCodigo200() throws Exception {
        when(nfeInutilizacaoService.inutilizar(any()))
                .thenReturn("102 - Inutilização de número homologado");

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("102 - Inutilização de número homologado"));
    }

    @Test
    @WithMockUser
    void inutilizar_dadosInvalidos_retornaErro500() throws Exception {
        when(nfeInutilizacaoService.inutilizar(any()))
                .thenThrow(new IllegalArgumentException("Série inválida para inutilização"));

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    @Test
    void semToken_retorna401() throws Exception {
        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_VALIDO))
                .andExpect(status().isUnauthorized());
    }

    private static final String BODY_COM_CNPJ = """
            {
              "ano": "24",
              "serie": "1",
              "nNFIni": "100",
              "nNFFin": "105",
              "justificativa": "Inutilizacao de faixa numerica para testes automatizados ERP",
              "cnpjEmitente": "22418179000134"
            }
            """;

    /** cnpjEmitente informado usa a empresa e o certificado explicitamente selecionados, não o global. */
    @Test
    @WithMockUser
    void inutilizar_comCnpjEmitente_usaEmpresaSelecionada() throws Exception {
        String cnpj = "22418179000134";
        Empresa empresaB = new Empresa();
        empresaB.setId(8L);
        empresaB.setCnpj(cnpj);
        empresaB.setUf("SP");
        CertificadoContexto certB = new CertificadoContexto(8L, null, null, null);

        when(empresaMapper.buscarPorCnpj(cnpj)).thenReturn(empresaB);
        when(empresaCertificadoService.resolverPorEmpresa(empresaB)).thenReturn(java.util.Optional.of(certB));
        when(nfeInutilizacaoService.inutilizar(any(), eq(cnpj), eq("SP"), eq(certB)))
                .thenReturn("102 - Inutilização de número homologado");

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_COM_CNPJ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(nfeInutilizacaoService, never()).inutilizar(any());
    }

    /** cnpjEmitente informado, mas sem certificado cadastrado — falha explícita, nunca cai no global. */
    @Test
    @WithMockUser
    void inutilizar_comCnpjEmitente_semCertificado_falhaExplicita() throws Exception {
        String cnpj = "22418179000134";
        Empresa empresaB = new Empresa();
        empresaB.setId(8L);
        empresaB.setCnpj(cnpj);
        empresaB.setUf("SP");

        when(empresaMapper.buscarPorCnpj(cnpj)).thenReturn(empresaB);
        when(empresaCertificadoService.resolverPorEmpresa(empresaB)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/fiscal/nfe/inutilizar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY_COM_CNPJ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));

        verifyNoInteractions(nfeInutilizacaoService);
    }
}
