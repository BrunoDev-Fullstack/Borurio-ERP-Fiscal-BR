package br.com.borurio.web.controller;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.controller.app.PedidoController;
import br.com.borurio.web.service.OmsCertificadoService;
import br.com.borurio.web.service.PedidoEmissaoService;
import br.com.borurio.web.service.PedidoOperacaoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    @MockBean OmsCertificadoService omsCertificadoService;
    @MockBean JwtUtil jwtUtil;
    @MockBean UserDetailsService userDetailsService;

    @AfterEach
    void limparContexto() {
        EmpresaContextHolder.clear();
    }

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

    @Test
    @WithMockUser
    void situacao_comChaveNfe_returns200() throws Exception {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put("pedidoId", 1L);
        mapa.put("status", "AGUARDANDO");
        mapa.put("chaveNfe", "12345678901234567890123456789012345678901234");
        mapa.put("consultaSefaz", "<retConsSitNFe/>");
        when(pedidoOperacaoService.consultarSituacao(1L)).thenReturn(mapa);

        mockMvc.perform(get("/api/app/pedidos/1/situacao"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("AGUARDANDO"))
                .andExpect(jsonPath("$.data.chaveNfe").exists());
    }

    @Test
    @WithMockUser
    void cancelar_valido_returns200() throws Exception {
        when(pedidoOperacaoService.cancelar(anyLong(), anyString())).thenReturn("<retEvento/>");

        mockMvc.perform(post("/api/app/pedidos/1/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\": \"Erro no pedido cancelado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void cancelar_justificativaCurta_returns400() throws Exception {
        when(pedidoOperacaoService.cancelar(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("Justificativa deve ter no mínimo 15 caracteres."));

        mockMvc.perform(post("/api/app/pedidos/1/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\": \"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void cce_valido_returns200() throws Exception {
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString())).thenReturn("<retEvento/>");

        mockMvc.perform(post("/api/app/pedidos/1/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correcao\": \"Correção de campo errado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @WithMockUser
    void cce_correcaoCurta_returns400() throws Exception {
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString()))
                .thenThrow(new IllegalArgumentException("Correção deve ter no mínimo 15 caracteres."));

        mockMvc.perform(post("/api/app/pedidos/1/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correcao\": \"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // -------------------------------------------------------------------------
    // externalOrderId — idempotência
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_comExternalOrderId_retornaExternalOrderIdNaResposta() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");

        Pedido pedido = new Pedido();
        pedido.setId(42L);
        pedido.setExternalOrderId("ORDER-XLI-2026-001");
        pedido.setDestCnpjCpf("12345678000195");
        pedido.setDestRazaoSocial("Cliente Teste");
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "externalOrderId": "ORDER-XLI-2026-001",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.externalOrderId").value("ORDER-XLI-2026-001"))
                .andExpect(jsonPath("$.data.status").value("RASCUNHO"));
    }

    // -------------------------------------------------------------------------
    // Endereço do emitente no payload do pedido — completa cadastro incompleto (fluxo OMS)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_omsComEnderecoEEmpresaIncompleta_completaCadastro() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaIncompleta = new Empresa();
        empresaIncompleta.setId(8L);
        empresaIncompleta.setCnpj("22418179000134");
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaIncompleta);

        Pedido pedido = new Pedido();
        pedido.setId(1L);
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP",
                                  "emitLogradouro": "PRATES",
                                  "emitNumero": "447",
                                  "emitBairro": "BOM RETIRO",
                                  "emitCodigoMunicipio": "3550308",
                                  "emitMunicipio": "São Paulo",
                                  "emitCep": "01121000"
                                }
                                """))
                .andExpect(status().isOk());

        verify(empresaService).atualizar(8L, empresaIncompleta);
        org.junit.jupiter.api.Assertions.assertEquals("PRATES", empresaIncompleta.getLogradouro());
        org.junit.jupiter.api.Assertions.assertEquals("BOM RETIRO", empresaIncompleta.getBairro());
    }

    @Test
    @WithMockUser
    void criar_omsComEnderecoMasEmpresaJaCompleta_naoAlteraCadastro() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaCompleta = new Empresa();
        empresaCompleta.setId(1L);
        empresaCompleta.setCnpj("22418179000134");
        empresaCompleta.setLogradouro("Rua Já Cadastrada");
        empresaCompleta.setNumero("100");
        empresaCompleta.setBairro("Centro");
        empresaCompleta.setCodigoMunicipio("3550308");
        empresaCompleta.setMunicipio("São Paulo");
        empresaCompleta.setCep("01000000");
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaCompleta);

        Pedido pedido = new Pedido();
        pedido.setId(2L);
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP",
                                  "emitLogradouro": "OUTRA RUA",
                                  "emitNumero": "999"
                                }
                                """))
                .andExpect(status().isOk());

        verify(empresaService, never()).atualizar(anyLong(), any());
        org.junit.jupiter.api.Assertions.assertEquals("Rua Já Cadastrada", empresaCompleta.getLogradouro());
    }

    @Test
    @WithMockUser
    void criar_omsSemEnderecoNoPayload_naoAlteraCadastro() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaIncompleta = new Empresa();
        empresaIncompleta.setId(8L);
        empresaIncompleta.setCnpj("22418179000134");
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaIncompleta);

        Pedido pedido = new Pedido();
        pedido.setId(3L);
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk());

        verify(empresaService, never()).atualizar(anyLong(), any());
    }

    // -------------------------------------------------------------------------
    // P0.3 — série padrão da empresa emitente correta (multi-CNPJ)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_semSerieNfe_usaSeriePadraoDaEmpresaDoCnpjDoPedido() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaB = new Empresa();
        empresaB.setId(8L);
        empresaB.setCnpj("22418179000134");
        empresaB.setSerieNfePadrao("2");
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaB);

        Pedido retorno = new Pedido();
        retorno.setId(1L);
        retorno.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(retorno);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService).criar(captor.capture(), any());
        assertEquals("2", captor.getValue().getSerieNfe());
    }

    @Test
    @WithMockUser
    void criar_comSerieNfeExplicita_preservaSerieRecebidaMesmoComSeriePadraoDiferente() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaB = new Empresa();
        empresaB.setId(8L);
        empresaB.setCnpj("22418179000134");
        empresaB.setSerieNfePadrao("2");
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaB);

        Pedido retorno = new Pedido();
        retorno.setId(1L);
        retorno.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(retorno);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "serieNfe": "3",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService).criar(captor.capture(), any());
        assertEquals("3", captor.getValue().getSerieNfe());
    }

    @Test
    @WithMockUser
    void criar_semSerieNfe_empresaSemSeriePadrao_deixaParaFallbackDoService() throws Exception {
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", "22418179000134"))
                .thenReturn(true);

        Empresa empresaSemSerie = new Empresa();
        empresaSemSerie.setId(8L);
        empresaSemSerie.setCnpj("22418179000134");
        // serieNfePadrao propositalmente não setado (null)
        when(empresaService.buscarPorCnpj("22418179000134")).thenReturn(empresaSemSerie);

        Pedido retorno = new Pedido();
        retorno.setId(1L);
        retorno.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(retorno);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk());

        // Controller não resolveu nada aqui — o fallback "1" é responsabilidade do
        // PedidoServiceImpl.criar() (não mockado em profundidade neste teste de controller).
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService).criar(captor.capture(), any());
        assertNull(captor.getValue().getSerieNfe());
    }

    @Test
    @WithMockUser
    void criar_doisCnpjsDiferentes_cadaUmUsaSuaPropriaSeriePadrao() throws Exception {
        String cnpjA = "54393421000159";
        String cnpjB = "22418179000134";

        Empresa empresaA = new Empresa();
        empresaA.setId(1L);
        empresaA.setCnpj(cnpjA);
        empresaA.setSerieNfePadrao("1");

        Empresa empresaB = new Empresa();
        empresaB.setId(8L);
        empresaB.setCnpj(cnpjB);
        empresaB.setSerieNfePadrao("2");

        when(empresaService.buscarPorCnpj(cnpjA)).thenReturn(empresaA);
        when(empresaService.buscarPorCnpj(cnpjB)).thenReturn(empresaB);

        Pedido retornoA = new Pedido();
        retornoA.setId(1L);
        retornoA.setStatus("RASCUNHO");
        Pedido retornoB = new Pedido();
        retornoB.setId(2L);
        retornoB.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(retornoA).thenReturn(retornoB);

        // Pedido da empresa A
        EmpresaContextHolder.setJtiAuth("jti-oms-teste");
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", cnpjA)).thenReturn(true);
        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "%s",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """.formatted(cnpjA)))
                .andExpect(status().isOk());

        // Pedido da empresa B, mesma sessão de teste
        when(omsCertificadoService.cnpjAutorizadoParaJti("jti-oms-teste", cnpjB)).thenReturn(true);
        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "%s",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """.formatted(cnpjB)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService, org.mockito.Mockito.times(2)).criar(captor.capture(), any());
        List<Pedido> capturados = captor.getAllValues();

        Pedido pedidoDaEmpresaA = capturados.stream().filter(p -> cnpjA.equals(p.getCnpjEmitente())).findFirst().orElseThrow();
        Pedido pedidoDaEmpresaB = capturados.stream().filter(p -> cnpjB.equals(p.getCnpjEmitente())).findFirst().orElseThrow();

        assertEquals("1", pedidoDaEmpresaA.getSerieNfe(), "pedido da empresa A precisa usar a série padrão de A");
        assertEquals("2", pedidoDaEmpresaB.getSerieNfe(), "pedido da empresa B precisa usar a série padrão de B, nunca a de A");
    }

    // -------------------------------------------------------------------------
    // errorCode — BusinessException
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_estoqueInsuficiente_returns422ComErrorCode() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any())).thenThrow(
                new BusinessException("INSUFFICIENT_STOCK",
                        "Estoque insuficiente para \"Prod A\" (disponível: 0.00, solicitado: 5.00)", 422));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{ "produtoId": 1, "quantidade": 5, "valorUnitario": 10.00 }]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"));
    }

    @Test
    @WithMockUser
    void criar_produtoInativo_returns422ComErrorCode() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any())).thenThrow(
                new BusinessException("PRODUCT_INACTIVE",
                        "Produto inativo não pode ser adicionado ao pedido: id=3 código=PROD-003", 422));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{ "produtoId": 3, "quantidade": 1, "valorUnitario": 10.00 }]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_INACTIVE"));
    }

    @Test
    @WithMockUser
    void criar_produtoNaoEncontrado_returns422ComErrorCode() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any())).thenThrow(
                new BusinessException("PRODUCT_NOT_FOUND",
                        "Produto não encontrado: id=99", 422));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{ "produtoId": 99, "quantidade": 1, "valorUnitario": 10.00 }]
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }

    // -------------------------------------------------------------------------
    // Override fiscal por item — dados fiscais vêm do OMS no payload do pedido
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_comCamposFiscaisNoItem_returns200() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");

        Pedido pedido = new Pedido();
        pedido.setId(10L);
        pedido.setDestCnpjCpf("12345678000195");
        pedido.setDestRazaoSocial("Cliente Fiscal");
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Fiscal",
                                  "destUf": "SP",
                                  "itens": [{
                                    "produtoId": 1,
                                    "quantidade": 2,
                                    "valorUnitario": 50.00,
                                    "codigoProduto": "SKU-OMS-001",
                                    "descricao": "Produto OMS",
                                    "ncm": "84715011",
                                    "cfop": "6102",
                                    "unidade": "UN",
                                    "origem": 0,
                                    "csosn": "102"
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.status").value("RASCUNHO"));
    }

    @Test
    @WithMockUser
    void criar_cfopAusenteNoItem_returns400() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any()))
                .thenThrow(new IllegalArgumentException("cfop é obrigatório no item produtoId=1"));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{
                                    "produtoId": 1,
                                    "quantidade": 1,
                                    "valorUnitario": 10.00,
                                    "codigoProduto": "SKU-001",
                                    "descricao": "Produto",
                                    "ncm": "84715011",
                                    "unidade": "UN",
                                    "origem": 0,
                                    "csosn": "400"
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void criar_ncmAusenteNoItem_returns400() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any()))
                .thenThrow(new IllegalArgumentException("ncm é obrigatório no item produtoId=1"));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{
                                    "produtoId": 1,
                                    "quantidade": 1,
                                    "valorUnitario": 10.00,
                                    "codigoProduto": "SKU-001",
                                    "descricao": "Produto",
                                    "cfop": "5102",
                                    "unidade": "UN",
                                    "origem": 0,
                                    "csosn": "400"
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void criar_codigoProdutoAusenteNoItem_returns400() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        when(pedidoService.criar(any(), any()))
                .thenThrow(new IllegalArgumentException("codigoProduto é obrigatório no item produtoId=1"));

        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente",
                                  "itens": [{
                                    "produtoId": 1,
                                    "quantidade": 1,
                                    "valorUnitario": 10.00,
                                    "descricao": "Produto",
                                    "ncm": "84715011",
                                    "cfop": "5102",
                                    "unidade": "UN",
                                    "origem": 0,
                                    "csosn": "400"
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @WithMockUser
    void emitir_pedidoNaoRascunho_returns422ComErrorCode() throws Exception {
        when(pedidoEmissaoService.emitir(1L)).thenThrow(
                new BusinessException("INVALID_ORDER_STATUS",
                        "Pedido não está em RASCUNHO. Status atual: AUTORIZADO", 422));

        mockMvc.perform(post("/api/app/pedidos/1/emitir")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ORDER_STATUS"));
    }

    @Test
    @WithMockUser
    void emitir_pedidoNaoRascunho_returns422() throws Exception {
        when(pedidoEmissaoService.emitir(1L))
                .thenThrow(new IllegalStateException("Pedido não está em RASCUNHO."));

        mockMvc.perform(post("/api/app/pedidos/1/emitir")
                        .with(csrf()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    @WithMockUser
    void cancelar_statusErrado_returns422() throws Exception {
        when(pedidoOperacaoService.cancelar(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("Cancelamento só é permitido para pedidos AUTORIZADOS."));

        mockMvc.perform(post("/api/app/pedidos/1/cancelar")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justificativa\": \"Justificativa válida longa\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    @Test
    @WithMockUser
    void cce_statusErrado_returns422() throws Exception {
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString()))
                .thenThrow(new IllegalStateException("CC-e só é permitida para pedidos AUTORIZADOS."));

        mockMvc.perform(post("/api/app/pedidos/1/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correcao\": \"Correção de campo válida\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }
}
