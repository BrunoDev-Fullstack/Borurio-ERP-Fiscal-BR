package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.web.dto.EmpresaAtualizacaoRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Rota B (13-08-2026) — regras de merge campo a campo de {@link EmpresaAtualizacaoService}.
 * Nível de teste correto pra provar cada regra isoladamente (sem depender de MySQL real, que
 * cobre a persistência de ponta a ponta em {@code EmpresaAtualizacaoParcialRealMySqlIT}).
 *
 * A leitura passou a ser {@link EmpresaMapper#buscarPorIdParaAtualizar} (lock, 14-08-2026, correção
 * do lost update) — {@code empresaService.buscarPorId} não é mais usado por {@code aplicar()}.
 * {@link PlatformTransactionManager} é mockado (mesmo padrão de OmsAuthorizationAdminServiceTest):
 * o {@link org.springframework.transaction.support.TransactionTemplate} real do service só precisa
 * de um {@code TransactionStatus} não-nulo para executar o callback — a fronteira transacional de
 * verdade (mesma conexão para leitura bloqueada + UPDATE) é provada contra MySQL real em
 * {@code EmpresaAtualizacaoLostUpdateRealMySqlIT}, não aqui.
 */
@ExtendWith(MockitoExtension.class)
class EmpresaAtualizacaoServiceTest {

    @Mock EmpresaService empresaService;
    @Mock EmpresaMapper empresaMapper;
    @Mock CertSenhaEncryptor encryptor;
    @Mock PlatformTransactionManager transactionManager;

    EmpresaAtualizacaoService service;

    private static final Long ID = 1L;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new EmpresaAtualizacaoService(empresaService, empresaMapper, encryptor, transactionManager);
    }

    private Empresa empresaPersistida() {
        Empresa e = new Empresa();
        e.setId(ID);
        e.setCnpj("54393421000159");
        e.setRazaoSocial("JCHO GLOBAL LTDA");
        e.setCrt("1");
        e.setUf("SP");
        e.setSerieNfePadrao("1");
        e.setIndFinalPadrao("1");
        e.setAtivo(true);
        e.setControleEstoqueAtivo(false);
        e.setNomeFantasia("JCHO");
        e.setIe("123456789");
        e.setLogradouro("Rua Teste");
        e.setCertPath("/antigo.pfx");
        e.setCertSenha("ENC(valorAntigo)");
        e.setCertTipo("PKCS12");
        return e;
    }

    private void stubBuscarEAtualizar(Empresa persistida) {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(persistida);
        when(empresaService.atualizar(eq(ID), any(Empresa.class))).thenAnswer(inv -> inv.getArgument(1));
    }

    // -------------------------------------------------------------------------
    // Payload só de certificado — o caso documentado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("payload só de certificado: service é alcançado e todos os demais campos permanecem inalterados")
    void aplicar_soCertificado_preservaTodosOsDemaisCampos() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);
        when(encryptor.encrypt("senha_plaintext")).thenReturn("ENC(novoValor)");

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/app/certificados/pfx/empresa-X.pfx");
        req.setCertSenha("senha_plaintext");
        req.setCertTipo("PKCS12");

        Empresa resultado = service.aplicar(ID, req);

        verify(empresaService).atualizar(eq(ID), any(Empresa.class));
        assertEquals(persistida.getCnpj(), resultado.getCnpj());
        assertEquals(persistida.getRazaoSocial(), resultado.getRazaoSocial());
        assertEquals(persistida.getCrt(), resultado.getCrt());
        assertEquals(persistida.getUf(), resultado.getUf());
        assertEquals(persistida.getSerieNfePadrao(), resultado.getSerieNfePadrao());
        assertEquals(persistida.getIndFinalPadrao(), resultado.getIndFinalPadrao());
        assertEquals(persistida.getAtivo(), resultado.getAtivo());
        assertEquals(persistida.getNomeFantasia(), resultado.getNomeFantasia());
        assertEquals(persistida.getIe(), resultado.getIe());
        assertEquals(persistida.getLogradouro(), resultado.getLogradouro());
        assertEquals("/app/certificados/pfx/empresa-X.pfx", resultado.getCertPath());
        assertEquals("ENC(novoValor)", resultado.getCertSenha());
        assertEquals("PKCS12", resultado.getCertTipo());
    }

    @Test
    @DisplayName("controleEstoqueAtivo=false permanece false depois de atualizar somente certificado")
    void aplicar_soCertificado_controleEstoqueAtivoFalse_permaneceFalse() {
        Empresa persistida = empresaPersistida();
        persistida.setControleEstoqueAtivo(false);
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx");

        Empresa resultado = service.aplicar(ID, req);

        assertEquals(false, resultado.getControleEstoqueAtivo());
    }

    @Test
    @DisplayName("controleEstoqueAtivo=true permanece true depois de atualizar somente certificado")
    void aplicar_soCertificado_controleEstoqueAtivoTrue_permaneceTrue() {
        Empresa persistida = empresaPersistida();
        persistida.setControleEstoqueAtivo(true);
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx");

        Empresa resultado = service.aplicar(ID, req);

        assertEquals(true, resultado.getControleEstoqueAtivo());
    }

    @Test
    @DisplayName("alteração explícita de controleEstoqueAtivo funciona")
    void aplicar_controleEstoqueAtivoExplicito_atualiza() {
        Empresa persistida = empresaPersistida();
        persistida.setControleEstoqueAtivo(true);
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setControleEstoqueAtivo(false);

        Empresa resultado = service.aplicar(ID, req);

        assertEquals(false, resultado.getControleEstoqueAtivo());
    }

    // -------------------------------------------------------------------------
    // Campos obrigatórios — null explícito vs. omitido vs. vazio
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null explícito em campo obrigatório (razaoSocial) -> BusinessException 422, service.atualizar nunca chamado")
    void aplicar_razaoSocialNullExplicito_lancaExcecaoSemGravar() {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(empresaPersistida());

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setRazaoSocial(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(ID, req));

        assertEquals("EMPRESA_CAMPO_OBRIGATORIO_NULO", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verify(empresaService, never()).atualizar(any(), any());
    }

    @Test
    @DisplayName("valor vazio em campo obrigatório (uf em branco) -> BusinessException 422, service.atualizar nunca chamado")
    void aplicar_ufVazio_lancaExcecaoSemGravar() {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(empresaPersistida());

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setUf("   ");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(ID, req));

        assertEquals("EMPRESA_CAMPO_OBRIGATORIO_INVALIDO", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verify(empresaService, never()).atualizar(any(), any());
    }

    @Test
    @DisplayName("null explícito em ativo/controleEstoqueAtivo -> 422, service.atualizar nunca chamado")
    void aplicar_booleanoObrigatorioNullExplicito_lancaExcecao() {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(empresaPersistida());

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setControleEstoqueAtivo(null); // presente=true, valor=null (diferente de omitido)

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(ID, req));

        assertEquals("EMPRESA_CAMPO_OBRIGATORIO_NULO", ex.getErrorCode());
        verify(empresaService, never()).atualizar(any(), any());
    }

    @Test
    @DisplayName("campos obrigatórios omitidos preservam o valor persistido")
    void aplicar_obrigatoriosOmitidos_preserva() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx"); // único campo presente

        Empresa resultado = service.aplicar(ID, req);

        assertEquals(persistida.getRazaoSocial(), resultado.getRazaoSocial());
        assertEquals(persistida.getCrt(), resultado.getCrt());
        assertEquals(persistida.getUf(), resultado.getUf());
        assertEquals(persistida.getSerieNfePadrao(), resultado.getSerieNfePadrao());
        assertEquals(persistida.getIndFinalPadrao(), resultado.getIndFinalPadrao());
        assertEquals(persistida.getAtivo(), resultado.getAtivo());
        assertEquals(persistida.getControleEstoqueAtivo(), resultado.getControleEstoqueAtivo());
    }

    // -------------------------------------------------------------------------
    // Campos nullable — omitido preserva, null explícito limpa
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("null explícito em campo nullable (nomeFantasia) -> limpa o campo")
    void aplicar_nomeFantasiaNullExplicito_limpa() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setNomeFantasia(null);

        Empresa resultado = service.aplicar(ID, req);

        assertNull(resultado.getNomeFantasia());
    }

    @Test
    @DisplayName("campo nullable omitido preserva o valor persistido")
    void aplicar_nomeFantasiaOmitido_preserva() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx"); // único campo presente

        Empresa resultado = service.aplicar(ID, req);

        assertEquals(persistida.getNomeFantasia(), resultado.getNomeFantasia());
        assertEquals(persistida.getIe(), resultado.getIe());
        assertEquals(persistida.getLogradouro(), resultado.getLogradouro());
    }

    @Test
    @DisplayName("null explícito em certTipo -> limpa (código de certificado já trata null como fallback PKCS12, comportamento seguro)")
    void aplicar_certTipoNullExplicito_limpa() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertTipo(null);

        Empresa resultado = service.aplicar(ID, req);

        assertNull(resultado.getCertTipo());
    }

    // -------------------------------------------------------------------------
    // certSenha — nunca reencripta valor preservado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("certSenha omitida: preserva o valor já criptografado sem chamar o encryptor de novo")
    void aplicar_certSenhaOmitida_preservaSemReencriptar() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx");

        Empresa resultado = service.aplicar(ID, req);

        assertEquals("ENC(valorAntigo)", resultado.getCertSenha());
        verify(encryptor, never()).encrypt(any());
    }

    @Test
    @DisplayName("certSenha presente com valor novo: criptografa antes de persistir")
    void aplicar_certSenhaPresente_criptografaAntesDePersistir() {
        Empresa persistida = empresaPersistida();
        stubBuscarEAtualizar(persistida);
        when(encryptor.encrypt("nova_senha_plaintext")).thenReturn("ENC(novo)");

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertSenha("nova_senha_plaintext");

        Empresa resultado = service.aplicar(ID, req);

        assertEquals("ENC(novo)", resultado.getCertSenha());
        verify(encryptor).encrypt("nova_senha_plaintext");
    }

    // -------------------------------------------------------------------------
    // CNPJ — imutável por este endpoint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tentativa de trocar CNPJ -> BusinessException 422, service.atualizar nunca chamado")
    void aplicar_cnpjDiferente_lancaExcecaoSemGravar() {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(empresaPersistida());

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCnpj("11222333000181"); // diferente do persistido

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(ID, req));

        assertEquals("EMPRESA_CNPJ_IMUTAVEL", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verify(empresaService, never()).atualizar(any(), any());
    }

    @Test
    @DisplayName("mesmo CNPJ informado (já normalizado ou com máscara) -> aceito, sem efeito colateral")
    void aplicar_mesmoCnpjComMascara_semEfeitoColateral() {
        Empresa persistida = empresaPersistida(); // cnpj = 54393421000159
        stubBuscarEAtualizar(persistida);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCnpj("54.393.421/0001-59"); // mesmo CNPJ, com máscara — precisa normalizar igual

        Empresa resultado = service.aplicar(ID, req);

        assertEquals("54393421000159", resultado.getCnpj());
    }

    @Test
    @DisplayName("null explícito em cnpj -> 422, service.atualizar nunca chamado")
    void aplicar_cnpjNullExplicito_lancaExcecao() {
        when(empresaMapper.buscarPorIdParaAtualizar(ID)).thenReturn(empresaPersistida());

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCnpj(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(ID, req));

        assertEquals("EMPRESA_CAMPO_OBRIGATORIO_NULO", ex.getErrorCode());
        verify(empresaService, never()).atualizar(any(), any());
    }

    // -------------------------------------------------------------------------
    // Isolamento entre empresas
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("isolamento: atualizar a empresa 1 nunca lê nem grava a empresa 2")
    void aplicar_duasEmpresas_naoInfluenciamUmaAOutra() {
        Empresa empresa1 = empresaPersistida();
        empresa1.setId(1L);
        Empresa empresa2 = empresaPersistida();
        empresa2.setId(2L);
        empresa2.setCnpj("11222333000181");
        empresa2.setControleEstoqueAtivo(true);

        when(empresaMapper.buscarPorIdParaAtualizar(1L)).thenReturn(empresa1);
        when(empresaMapper.buscarPorIdParaAtualizar(2L)).thenReturn(empresa2);
        when(empresaService.atualizar(eq(1L), any(Empresa.class))).thenAnswer(inv -> inv.getArgument(1));
        when(empresaService.atualizar(eq(2L), any(Empresa.class))).thenAnswer(inv -> inv.getArgument(1));

        EmpresaAtualizacaoRequest req1 = new EmpresaAtualizacaoRequest();
        req1.setControleEstoqueAtivo(true);
        EmpresaAtualizacaoRequest req2 = new EmpresaAtualizacaoRequest();
        req2.setCertPath("/empresa2.pfx");

        Empresa resultado1 = service.aplicar(1L, req1);
        Empresa resultado2 = service.aplicar(2L, req2);

        assertEquals(true, resultado1.getControleEstoqueAtivo());
        assertEquals("11222333000181", resultado2.getCnpj());
        assertEquals(true, resultado2.getControleEstoqueAtivo(), "empresa 2 nunca deveria ter sido tocada pelo request da empresa 1");
        verify(empresaMapper, never()).buscarPorIdParaAtualizar(3L);

        ArgumentCaptor<Empresa> captor = ArgumentCaptor.forClass(Empresa.class);
        verify(empresaService).atualizar(eq(1L), captor.capture());
        assertEquals(1L, captor.getValue().getId());
        verify(empresaService).atualizar(eq(2L), captor.capture());
        assertEquals(2L, captor.getValue().getId());
    }
}
