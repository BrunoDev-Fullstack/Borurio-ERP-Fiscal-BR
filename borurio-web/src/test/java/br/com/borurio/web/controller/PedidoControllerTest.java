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
import br.com.borurio.web.auth.OmsTokenAuthorizationValidator;
import br.com.borurio.web.config.SecurityConfig;
import br.com.borurio.web.controller.app.PedidoController;
import br.com.borurio.web.service.OmsCertificadoService;
import br.com.borurio.web.service.PedidoEmissaoService;
import br.com.borurio.web.service.PedidoOperacaoService;
import br.com.borurio.web.service.ValidacaoTextoFiscalPedido;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PedidoController.class)
@Import({SecurityConfig.class, ValidacaoTextoFiscalPedido.class})
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
    @MockBean OmsTokenAuthorizationValidator omsTokenAuthorizationValidator;

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
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString(), any())).thenReturn("<retEvento/>");

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
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString(), any()))
                .thenThrow(new IllegalArgumentException("Correção deve ter no mínimo 15 caracteres."));

        mockMvc.perform(post("/api/app/pedidos/1/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correcao\": \"curta\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // -------------------------------------------------------------------------
    // Correção controlada (03-09-2026, V1 — acordo com OMS)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void corrigir_valido_returns200() throws Exception {
        Pedido corrigido = new Pedido();
        corrigido.setId(1L);
        corrigido.setStatus("REJEITADO");
        when(pedidoOperacaoService.corrigir(eq(1L), any())).thenReturn(corrigido);

        mockMvc.perform(patch("/api/app/pedidos/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"destRazaoSocial\": \"Cliente Corrigido Ltda\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1));
    }

    @Test
    @WithMockUser
    void corrigir_statusErrado_returns422() throws Exception {
        when(pedidoOperacaoService.corrigir(eq(1L), any()))
                .thenThrow(BusinessException.invalidOrderStatus(
                        "Correção só é permitida para pedidos RASCUNHO/REJEITADO/ERRO. Status atual: AUTORIZADO"));

        mockMvc.perform(patch("/api/app/pedidos/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacao\": \"tentando corrigir autorizado\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ORDER_STATUS"));
    }

    @Test
    @WithMockUser
    void corrigir_itemNaoPertenceAoPedido_returns400() throws Exception {
        when(pedidoOperacaoService.corrigir(eq(1L), any()))
                .thenThrow(new IllegalArgumentException("Item id=999 não pertence ao pedido 1"));

        mockMvc.perform(patch("/api/app/pedidos/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itens\": [{\"id\": 999, \"descricao\": \"nova descrição\"}]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void corrigir_pedidoDeOutraEmpresa_returns404() throws Exception {
        when(pedidoOperacaoService.corrigir(eq(1L), any()))
                .thenThrow(new NoSuchElementException("Pedido não encontrado: id=1"));

        mockMvc.perform(patch("/api/app/pedidos/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"observacao\": \"correção\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void corrigir_itemSemId_returns422() throws Exception {
        mockMvc.perform(patch("/api/app/pedidos/1")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itens\": [{\"descricao\": \"sem id\"}]}"))
                .andExpect(status().isUnprocessableEntity());
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
    // Série/numeração — 20-07-2026: NÃO é mais resolvida na criação do pedido.
    // ReservaFiscalService resolve série (a partir de Empresa.serieNfePadrao) + número juntos,
    // atomicamente, só no INÍCIO da emissão — nunca na criação. Isso corrige o gap em que um
    // pedido criado ANTES de uma sincronização de série via OMS, mas emitido DEPOIS, continuava
    // usando a série antiga (congelada na criação). Ver
    // docs/report/Desenho_Tecnico_Sincronizacao_Serie_Numeracao_2026-07-20.md seção 3.
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser
    void criar_serieNfeOuChaveNfeNoJson_saoIgnoradosSilenciosamente() throws Exception {
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

        // achado de 20-07-2026: chaveNfe também era bindável sem validação — DTO fecha os dois.
        mockMvc.perform(post("/api/app/pedidos")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "cnpjEmitente": "22418179000134",
                                  "serieNfe": "3",
                                  "chaveNfe": "35240711222333000181550010000001011234567890",
                                  "destCnpjCpf": "12345678000195",
                                  "destRazaoSocial": "Cliente Teste",
                                  "destUf": "SP"
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService).criar(captor.capture(), any());
        assertNull(captor.getValue().getSerieNfe(),
                "serieNfe do JSON precisa ser ignorado — PedidoCreateRequest não expõe o campo");
        assertNull(captor.getValue().getChaveNfe(),
                "chaveNfe do JSON precisa ser ignorado — nunca deveria ser bindável na criação");
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

        // Controller não resolve série nenhuma — placeholder "1" gravado no INSERT é
        // responsabilidade de PedidoServiceImpl.criar() (não mockado em profundidade aqui) e
        // nunca é lido para decidir a série de emissão (ver ReservaFiscalService).
        ArgumentCaptor<Pedido> captor = ArgumentCaptor.forClass(Pedido.class);
        verify(pedidoService).criar(captor.capture(), any());
        assertNull(captor.getValue().getSerieNfe());
    }

    @Test
    @WithMockUser
    void criar_doisCnpjsDiferentes_cadaUmResolveSeuProprioCnpjEmitente() throws Exception {
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

        // Série NÃO é mais resolvida aqui pra nenhum dos dois CNPJs — cada um resolve a sua
        // própria série padrão (potencialmente diferente) só quando efetivamente for emitido.
        assertNull(pedidoDaEmpresaA.getSerieNfe());
        assertNull(pedidoDaEmpresaB.getSerieNfe());
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
        when(pedidoOperacaoService.emitirCce(anyLong(), anyString(), any()))
                .thenThrow(new IllegalStateException("CC-e só é permitida para pedidos AUTORIZADOS."));

        mockMvc.perform(post("/api/app/pedidos/1/cce")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correcao\": \"Correção de campo válida\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(422));
    }

    // -------------------------------------------------------------------------
    // P0-2 (Gate 1.2 Fase B) — prova adversarial de ponta a ponta: requisição HTTP real,
    // passando pelo JwtFilter de PRODUÇÃO (não mockado — só JwtUtil/UserDetailsService são
    // @MockBean, exatamente como no resto desta classe), contra o PedidoController real.
    // Docker/MySQL não estão disponíveis nesta sessão (sem GATEB_DB_URL, sem daemon do Docker
    // rodando) — por isso a prova é feita neste slice @WebMvcTest em vez de um *IT com MySQL
    // real; ainda assim atravessa o JwtFilter real e o dispatch real do Spring MVC, não apenas
    // o método isolado. verifyNoInteractions comprova que nenhum service/efeito de negócio é
    // alcançado quando o filtro barra a requisição.
    // -------------------------------------------------------------------------

    private static final String TOKEN_P02 = "fake-jwt-p02";

    private UserDetails userDetailsComRole(String username, String role) {
        return User.builder()
                .username(username)
                .password("hash-irrelevante")
                .authorities(AuthorityUtils.createAuthorityList("ROLE_" + role))
                .build();
    }

    private void mockarUsuarioSemOms(String username, String role, Long empresaId) {
        when(jwtUtil.extractTipo(TOKEN_P02)).thenReturn(null);
        when(jwtUtil.extractUsername(TOKEN_P02)).thenReturn(username);
        when(jwtUtil.validateToken(TOKEN_P02, username)).thenReturn(true);
        when(jwtUtil.extractEmpresaId(TOKEN_P02)).thenReturn(empresaId);
        when(userDetailsService.loadUserByUsername(username)).thenReturn(userDetailsComRole(username, role));
    }

    @Test
    void operadorSemTenant_listar_403TenantRequiredAntesDoService() throws Exception {
        mockarUsuarioSemOms("operador-sem-empresa@teste.com", "OPERADOR", null);

        mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + TOKEN_P02))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false));

        verifyNoInteractions(pedidoService);
    }

    @Test
    void operadorSemTenant_emitir_403TenantRequiredSemNenhumEfeitoDeEmissao() throws Exception {
        mockarUsuarioSemOms("operador-sem-empresa@teste.com", "OPERADOR", null);

        mockMvc.perform(post("/api/app/pedidos/1/emitir")
                        .with(csrf())
                        .header("Authorization", "Bearer " + TOKEN_P02))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REQUIRED"));

        // Nenhum efeito de negócio: claim de emissão, estoque, nfe_emissao/nfe_sequencia e SEFAZ
        // vivem dentro de PedidoEmissaoService.emitir() — zero interação prova que a requisição
        // nunca saiu do JwtFilter, nem chegou ao controller/service.
        verifyNoInteractions(pedidoEmissaoService);
    }

    @Test
    void adminSemTenant_listar_acessoGlobalPermitido() throws Exception {
        mockarUsuarioSemOms("admin@teste.com", "ADMIN", null);
        when(pedidoService.listarPaginado(isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + TOKEN_P02))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(pedidoService).listarPaginado(isNull(), anyInt(), anyInt());
    }

    @Test
    void operadorComTenant_listar_permitidoEscopadoAEmpresa() throws Exception {
        mockarUsuarioSemOms("operador@teste.com", "OPERADOR", 10L);
        when(pedidoService.listarPaginado(org.mockito.ArgumentMatchers.eq(10L), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + TOKEN_P02))
                .andExpect(status().isOk());

        verify(pedidoService).listarPaginado(org.mockito.ArgumentMatchers.eq(10L), anyInt(), anyInt());
    }

    // -------------------------------------------------------------------------
    // Validação fiscal preventiva (02-09-2026) — FISCAL_TEXT_INVALID_CHARS no POST /pedidos.
    // Regra única: ValidacaoTextoFiscalPedido -> ValidadorTextoFiscalNfe (bean real via @Import).
    // -------------------------------------------------------------------------

    private static String bodyComDescricao(String descricao) {
        return """
                {
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Teste",
                  "destUf": "SP",
                  "naturezaOperacao": "VENDA DE MERCADORIA",
                  "itens": [
                    { "produtoId": 1, "codigoProduto": "SKU-1", "descricao": "%s",
                      "ncm": "01012900", "cfop": "5101", "unidade": "UN", "origem": 0, "csosn": "102",
                      "quantidade": 1, "valorUnitario": 10.0 }
                  ]
                }
                """.formatted(descricao);
    }

    @Test
    @WithMockUser
    void criar_descricaoChinesa_returns422_pedidoNaoCriado() throws Exception {
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyComDescricao("1喷油瓶-100ML（彩盒）-太空银")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("FISCAL_TEXT_INVALID_CHARS"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.data.field").value("itens[0].descricao"))
                .andExpect(jsonPath("$.data.itemIndex").value(0))
                .andExpect(jsonPath("$.data.reason").value("CARACTERE_NAO_PERMITIDO"));

        verify(pedidoService, never()).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_retryIdempotente_comTextoInvalido_naoRejeita_devolvePedidoExistente() throws Exception {
        // Pedido já existe para (externalOrderId, empresa) — a validação de criação NÃO roda:
        // a resposta correta de um retry idempotente é 200 + pedido existente, nunca 422 por
        // charset (ex.: pedido legado gravado antes desta regra). Autoridade de idempotência
        // continua em PedidoServiceImpl.criar; aqui só provamos que a V1 não intercepta o retry.
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");
        Pedido existente = new Pedido();
        existente.setId(67L);
        existente.setStatus("REJEITADO");
        existente.setExternalOrderId("ORDER-LEGADO-CHINES");
        when(pedidoService.buscarPorExternalOrderIdEEmpresa(eq("ORDER-LEGADO-CHINES"), any()))
                .thenReturn(existente);
        when(pedidoService.criar(any(), any())).thenReturn(existente);

        String body = """
                {
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Teste",
                  "destUf": "SP",
                  "externalOrderId": "ORDER-LEGADO-CHINES",
                  "itens": [
                    { "produtoId": 1, "codigoProduto": "SKU-1", "descricao": "喷油瓶-太空银",
                      "ncm": "01012900", "cfop": "5101", "unidade": "UN", "origem": 0, "csosn": "102",
                      "quantidade": 1, "valorUnitario": 10.0 }
                  ]
                }
                """;
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(67));

        verify(pedidoService).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_descricaoComEmoji_returns422() throws Exception {
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyComDescricao("Produto legal 😀")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("FISCAL_TEXT_INVALID_CHARS"));

        verify(pedidoService, never()).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_descricaoPortuguesComAcentos_returns200() throws Exception {
        when(emitente.getCnpj()).thenReturn("12.345.678/0001-95");

        Pedido pedido = new Pedido();
        pedido.setId(70L);
        pedido.setDestCnpjCpf("12345678000195");
        pedido.setDestRazaoSocial("Cliente Teste");
        pedido.setStatus("RASCUNHO");
        when(pedidoService.criar(any(), any())).thenReturn(pedido);

        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyComDescricao("Coração de melão à vontade - caixa prata")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(70));

        verify(pedidoService).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_naturezaOperacaoComCaractereInvalido_returns422() throws Exception {
        String body = """
                {
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Teste",
                  "destUf": "SP",
                  "naturezaOperacao": "Venda — mercadoria"
                }
                """;
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("FISCAL_TEXT_INVALID_CHARS"))
                .andExpect(jsonPath("$.data.field").value("naturezaOperacao"));

        verify(pedidoService, never()).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_descricaoAcimaDe120_returns422() throws Exception {
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyComDescricao("a".repeat(121))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("FISCAL_TEXT_INVALID_CHARS"));

        verify(pedidoService, never()).criar(any(), any());
    }

    @Test
    @WithMockUser
    void criar_segundoItemInvalido_apontaItemIndex1() throws Exception {
        String body = """
                {
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Teste",
                  "destUf": "SP",
                  "itens": [
                    { "produtoId": 1, "codigoProduto": "SKU-1", "descricao": "Produto valido",
                      "ncm": "01012900", "cfop": "5101", "unidade": "UN", "origem": 0, "csosn": "102",
                      "quantidade": 1, "valorUnitario": 10.0 },
                    { "produtoId": 2, "codigoProduto": "SKU-2", "descricao": "喷油瓶",
                      "ncm": "01012900", "cfop": "5101", "unidade": "UN", "origem": 0, "csosn": "102",
                      "quantidade": 1, "valorUnitario": 10.0 }
                  ]
                }
                """;
        mockMvc.perform(post("/api/app/pedidos").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.data.field").value("itens[1].descricao"))
                .andExpect(jsonPath("$.data.itemIndex").value(1));

        verify(pedidoService, never()).criar(any(), any());
    }
}
