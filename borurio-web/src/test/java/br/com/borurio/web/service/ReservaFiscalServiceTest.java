package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.ReservaFiscalResultado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservaFiscalService — reserva atômica série+número+snapshot no início de cada emissão")
class ReservaFiscalServiceTest {

    @Mock EmpresaMapper empresaMapper;
    @Mock NfeSequenciaService sequenciaService;
    @Mock PedidoMapper pedidoMapper;

    ReservaFiscalService service;

    private static final String CNPJ = "22418179000134";
    private static final Long PEDIDO_ID = 99L;

    @BeforeEach
    void setUp() {
        service = new ReservaFiscalService(empresaMapper, sequenciaService, pedidoMapper);
    }

    @Test
    @DisplayName("Resolve série a partir de Empresa.serieNfePadrao vigente, reserva o número e persiste o snapshot no pedido")
    void reservar_usaSeriePadraoDaEmpresaEPersisteSnapshot() {
        Empresa empresa = new Empresa();
        empresa.setId(8L);
        empresa.setSerieNfePadrao("2");
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa);
        when(sequenciaService.proximoNumero(CNPJ, "2")).thenReturn(15);

        ReservaFiscalResultado resultado = service.reservar(PEDIDO_ID, CNPJ);

        assertEquals("2", resultado.serie());
        assertEquals(15, resultado.numero());
        verify(pedidoMapper).atualizarSerieReservada(PEDIDO_ID, "2");
    }

    @Test
    @DisplayName("Ordem: resolve Empresa antes de reservar o número, e só persiste o snapshot depois de reservar")
    void reservar_ordemDeOperacoes() {
        Empresa empresa = new Empresa();
        empresa.setId(8L);
        empresa.setSerieNfePadrao("1");
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa);
        when(sequenciaService.proximoNumero(CNPJ, "1")).thenReturn(1);

        service.reservar(PEDIDO_ID, CNPJ);

        InOrder ordem = inOrder(empresaMapper, sequenciaService, pedidoMapper);
        ordem.verify(empresaMapper).buscarPorCnpjParaAtualizar(CNPJ);
        ordem.verify(sequenciaService).proximoNumero(CNPJ, "1");
        ordem.verify(pedidoMapper).atualizarSerieReservada(PEDIDO_ID, "1");
    }

    @Test
    @DisplayName("Empresa sem série padrão configurada usa fallback \"1\" (mesmo padrão legado)")
    void reservar_empresaSemSeriePadrao_usaFallback1() {
        Empresa empresa = new Empresa();
        empresa.setId(8L);
        empresa.setSerieNfePadrao(null);
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresa);
        when(sequenciaService.proximoNumero(CNPJ, "1")).thenReturn(1);

        ReservaFiscalResultado resultado = service.reservar(PEDIDO_ID, CNPJ);

        assertEquals("1", resultado.serie());
    }

    @Test
    @DisplayName("Empresa não encontrada lança COMPANY_NOT_FOUND — sem fallback silencioso, sem tocar sequência (correção pós-review 20-07-2026)")
    void reservar_empresaNaoEncontrada_lancaCompanyNotFound() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.reservar(PEDIDO_ID, CNPJ));

        assertEquals("COMPANY_NOT_FOUND", ex.getErrorCode());
        verify(sequenciaService, never()).proximoNumero(anyStringSafe(), anyStringSafe());
        verify(pedidoMapper, never()).atualizarSerieReservada(anyLongSafe(), anyStringSafe());
    }

    @Test
    @DisplayName("Reflete uma sincronização de série já aplicada — pedido criado antes, emitido depois, usa a série nova")
    void reservar_refleteSincronizacaoAplicadaAntesDaEmissao() {
        // Simula: Empresa.serieNfePadrao já foi atualizada por FiscalNumberingService ANTES
        // desta chamada — não importa quando o pedido foi criado, só a série vigente AGORA.
        Empresa empresaAposSync = new Empresa();
        empresaAposSync.setId(8L);
        empresaAposSync.setSerieNfePadrao("3"); // série nova, sincronizada pela OMS
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaAposSync);
        when(sequenciaService.proximoNumero(CNPJ, "3")).thenReturn(1);

        ReservaFiscalResultado resultado = service.reservar(PEDIDO_ID, CNPJ);

        assertEquals("3", resultado.serie(), "reserva precisa refletir a série vigente, não uma série congelada antes da sincronização");
    }

    // Helpers só pra evitar import estático conflitante de anyString()/anyLong() com o resto do arquivo.
    private static String anyStringSafe() { return org.mockito.ArgumentMatchers.anyString(); }
    private static long anyLongSafe() { return org.mockito.ArgumentMatchers.anyLong(); }
}
