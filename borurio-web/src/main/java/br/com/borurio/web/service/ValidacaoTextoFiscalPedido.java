package br.com.borurio.web.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe;
import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe.Campo;
import br.com.borurio.fiscal.utils.ValidadorTextoFiscalNfe.Violacao;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Integração fina (02-09-2026) da regra única {@link ValidadorTextoFiscalNfe} ao fluxo de pedido.
 * Aplica o validador aos campos de texto do {@link Pedido} destinados a elementos NF-e
 * {@code TString} e, na primeira violação, lança {@code FISCAL_TEXT_INVALID_CHARS} (HTTP 422,
 * {@code retryable=false}).
 *
 * <p>Chamado em dois pontos, ambos ANTES de qualquer efeito fiscal:
 * <ul>
 *   <li>{@code PedidoController.criar()} — antes de {@code pedidoService.criar()} (o pedido nem é
 *       persistido);</li>
 *   <li>{@code PedidoEmissaoService.emitir()} — antes de {@code NfeEmissaoService.abrirCiclo()}
 *       (defesa em profundidade para pedidos legados / dados que escaparam da criação — nenhum
 *       {@code nNF} é reservado, a SEFAZ não é chamada).</li>
 * </ul>
 *
 * <p>NÃO sanitiza, NÃO altera o pedido. Escopo V1: só charset + maxLength dos campos abaixo —
 * NCM, CFOP×destino, origem, CSOSN, CRT e unidade ficam de fora (regras próprias, banca V2).
 */
@Component
public class ValidacaoTextoFiscalPedido {

    /** Valida os textos fiscais do pedido (cabeçalho + itens). Lança na primeira violação. */
    public void validar(Pedido pedido) {
        if (pedido == null) {
            return;
        }

        checarCampo(Campo.NAT_OP,   "naturezaOperacao", pedido.getNaturezaOperacao());
        checarCampo(Campo.X_NOME,   "destRazaoSocial",  pedido.getDestRazaoSocial());
        checarCampo(Campo.X_LGR,    "destLogradouro",   pedido.getDestLogradouro());
        checarCampo(Campo.NRO,      "destNumero",       pedido.getDestNumero());
        checarCampo(Campo.X_BAIRRO, "destBairro",       pedido.getDestBairro());
        checarCampo(Campo.X_MUN,    "destMunicipio",    pedido.getDestMunicipio());
        checarCampo(Campo.INF_CPL,  "observacao",       pedido.getObservacao());

        List<PedidoItem> itens = pedido.getItens();
        if (itens != null) {
            for (int i = 0; i < itens.size(); i++) {
                PedidoItem item = itens.get(i);
                if (item != null) {
                    checarItem(i, item.getDescricao());
                }
            }
        }
    }

    private void checarCampo(Campo campo, String nomeCampo, String valor) {
        ValidadorTextoFiscalNfe.validar(campo, valor).ifPresent(v -> {
            throw BusinessException.fiscalTextInvalidChars(nomeCampo, null, v.motivo().name(), mensagem(v));
        });
    }

    private void checarItem(int index, String descricao) {
        ValidadorTextoFiscalNfe.validar(Campo.X_PROD, descricao).ifPresent(v -> {
            throw BusinessException.fiscalTextInvalidChars(
                    "itens[" + index + "].descricao", index, v.motivo().name(), mensagem(v));
        });
    }

    private String mensagem(Violacao v) {
        return switch (v.motivo()) {
            case CARACTERE_NAO_PERMITIDO -> "contém caractere não aceito pela NF-e — use apenas letras, "
                    + "números e pontuação padrão (sem caracteres de outros alfabetos ou emoji)";
            case ESPACO_NA_BORDA -> "não pode começar nem terminar com espaço";
            case ACIMA_DO_MAX_LENGTH -> "excede o tamanho máximo permitido pela NF-e ("
                    + v.maxLength() + " caracteres)";
        };
    }
}
