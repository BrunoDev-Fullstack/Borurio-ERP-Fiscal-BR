package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.ReservaFiscalResultado;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserva atômica de série + número no início de cada tentativa de emissão — nunca no momento
 * de criação do pedido. "Emissão iniciada" (para efeito da regra de sincronização confirmada
 * pelo CC) é definida como o instante em que esta reserva é feita, não o instante em que o
 * pedido é criado.
 *
 * Deliberadamente separado de NfeGeracaoService: esta transação só toca banco (Empresa +
 * nfe_sequencia + pedido), nunca a chamada HTTP à SEFAZ — segurar o lock de Empresa durante uma
 * chamada de rede externa travaria toda emissão concorrente do mesmo CNPJ por segundos.
 *
 * Ordem de lock (documentada para evitar deadlock com FiscalNumberingService, que faz a mesma
 * sequência para a sincronização vinda da OMS): sempre Empresa primeiro, depois nfe_sequencia.
 *
 * Correção de 20-07-2026 (revisão pós-implementação): a persistência do snapshot de série no
 * pedido (PedidoMapper.atualizarSerieReservada) foi movida PARA DENTRO desta mesma transação —
 * antes, ocorria numa chamada separada logo depois que este método retornava, criando uma janela
 * em que o número já estava consumido em nfe_sequencia mas o pedido ainda não tinha o snapshot
 * (e, pior: se essa segunda chamada falhasse, a exceção subia sem desfazer nada). Agora, se a
 * escrita no pedido falhar, a transação inteira é desfeita — inclusive o avanço da sequência —
 * então nunca existe um número "consumido" sem o snapshot correspondente.
 */
@Service
public class ReservaFiscalService {

    private final EmpresaMapper empresaMapper;
    private final NfeSequenciaService sequenciaService;
    private final PedidoMapper pedidoMapper;

    public ReservaFiscalService(EmpresaMapper empresaMapper, NfeSequenciaService sequenciaService,
                                 PedidoMapper pedidoMapper) {
        this.empresaMapper = empresaMapper;
        this.sequenciaService = sequenciaService;
        this.pedidoMapper = pedidoMapper;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ReservaFiscalResultado reservar(Long pedidoId, String cnpjEmitente) {
        Empresa empresa = empresaMapper.buscarPorCnpjParaAtualizar(cnpjEmitente);
        if (empresa == null) {
            // Fail-fast (mesmo princípio já adotado em P0.2 — sem fallback silencioso de
            // contexto multi-CNPJ): o fluxo OMS já valida CNPJ autorizado antes de chegar aqui
            // (PedidoEmissaoService.resolverEmpresaParaEmissao), e o fluxo interno tem Empresa
            // seedada no startup (StartupListener.seedEmpresaDefault()) — chegar aqui sem
            // Empresa é estado inconsistente, não um cenário legado válido a tolerar em silêncio.
            throw BusinessException.companyNotFound(cnpjEmitente);
        }
        String serie = (empresa.getSerieNfePadrao() != null && !empresa.getSerieNfePadrao().isBlank())
                ? empresa.getSerieNfePadrao()
                : "1"; // só quando a Empresa existe mas não tem série padrão configurada

        int numero = sequenciaService.proximoNumero(cnpjEmitente, serie);
        pedidoMapper.atualizarSerieReservada(pedidoId, serie);

        return new ReservaFiscalResultado(serie, numero);
    }
}
