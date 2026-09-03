package br.com.borurio.app.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.core.mvc.api.PageResponse;

import java.util.List;

public interface PedidoService {

    Pedido criar(Pedido pedido, List<PedidoItem> itens);

    /**
     * Idempotência de criação por (externalOrderId, empresaId) — mesma chave usada por
     * {@link #criar} para devolver o pedido já existente. Exposto para o chamador decidir, ANTES
     * de {@code criar}, se esta requisição vai realmente criar um pedido (ex.: pular validações
     * de criação num retry idempotente). {@code null} se não existe. {@code null} também quando
     * externalOrderId ou empresaId forem nulos/vazios (sem chave, sem idempotência).
     */
    Pedido buscarPorExternalOrderIdEEmpresa(String externalOrderId, Long empresaId);

    /** Retorna o pedido com a lista de itens preenchida. */
    Pedido buscarComItens(Long id);

    /** Retorna o pedido validando que pertence à empresa. */
    Pedido buscarComItensEEmpresa(Long id, Long empresaId);

    Pedido buscarPorId(Long id);

    /** Retorna o pedido (sem itens) validando que pertence à empresa. */
    Pedido buscarPorIdEEmpresa(Long id, Long empresaId);

    /**
     * P0-2 (07-08-2026, hardening pós-banca) — fronteira central de isolamento multiempresa
     * pra operações fiscais por pedidoId (emitir/cancelar/CC-e/situação). O tenant vem sempre de
     * EmpresaContextHolder (contexto autenticado, nunca de parâmetro/body/query) — se estiver
     * presente, valida posse (NoSuchElementException/404 se o pedido for de outra empresa); se
     * ausente (fluxo ADMIN interno, mesma convenção já usada em PedidoController.buscarPorId),
     * cai no comportamento irrestrito de antes.
     */
    Pedido buscarComItensDoTenanteAtual(Long id);

    /** Mesma fronteira de buscarComItensDoTenanteAtual(), sem carregar itens. */
    Pedido buscarPorIdDoTenanteAtual(Long id);

    List<Pedido> listarTodos();

    List<Pedido> listarPorEmpresa(Long empresaId);

    PageResponse<Pedido> listarPaginado(Long empresaId, int page, int size);

    void atualizarStatus(Long id, String status, String chaveNfe);

    /**
     * Reivindica atomicamente o pedido pra emissão (RASCUNHO/REJEITADO/ERRO → EMITINDO).
     * Retorna true se esta chamada venceu a corrida; false se outra requisição concorrente
     * já assumiu a emissão ou o status não é mais emissível. Ver PedidoMapper.reivindicarParaEmissao.
     */
    boolean reivindicarParaEmissao(Long id);

    /**
     * Correção controlada de texto fiscal (03-09-2026, V1, acordo com OMS) — permite reaproveitar
     * o mesmo {@code pedidoId}/{@code externalOrderId} quando o dado da criação veio incompatível
     * com o schema da NF-e (o snapshot fiscal é imutável por padrão). Só aplica quando o pedido
     * ainda está em RASCUNHO/REJEITADO/ERRO — mesmo guard atômico usado por
     * {@link #reivindicarParaEmissao}, nunca corre com uma reivindicação de emissão concorrente.
     *
     * <p>{@code cabecalhoMesclado} deve conter TODOS os campos do cabeçalho já mesclados
     * (existentes + correção) — o chamador decide o merge, esta camada só persiste. {@code
     * itensParaAtualizar} traz apenas os itens cuja {@code descricao} foi de fato corrigida (id +
     * descricao); itens não corrigidos não devem estar na lista.
     *
     * <p>Lança {@code BusinessException} ({@code INVALID_ORDER_STATUS}) se o status não permitir
     * mais a correção, e {@code IllegalArgumentException} se algum item não pertencer ao pedido.
     * Nunca toca em {@code nfe_emissao} — histórico fiscal de tentativas anteriores é preservado;
     * a próxima {@code /emitir} abre um ciclo novo com {@code nNF} novo, nunca reaproveitado.
     */
    Pedido corrigir(Long id, Pedido cabecalhoMesclado, List<PedidoItem> itensParaAtualizar);
}
