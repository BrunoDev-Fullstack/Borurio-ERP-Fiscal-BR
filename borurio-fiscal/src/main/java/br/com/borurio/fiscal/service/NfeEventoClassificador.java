package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeEvento;
import org.springframework.stereotype.Component;

/**
 * Matriz de classificacao do cStat de infEvento (cancelamento 110111) -- aprovada em 12-08-2026
 * apos revisao tecnica contra o catalogo oficial de cStat de evento. Deliberadamente separada de
 * qualquer parser XML: entrada e saida sao so o cStat (Integer), o que torna cada linha da matriz
 * testavel sem montar SOAP.
 *
 * Recebe exclusivamente um cStat INDIVIDUAL REAL do infEvento (ou do procEventoNFe equivalente,
 * na reconciliacao via Consulta Situacao) -- nunca o cStat DE LOTE (retEnvEvento/cStat, ex.
 * 128="lote processado") e nunca {@code null} como sinal de ausencia. A decisao sobre o que fazer
 * quando o infEvento esta ausente, incompleto, ou a resposta SOAP e ilegivel pertence ao
 * orquestrador (NfeEventoService/parser), que tem visibilidade do lote e da transmissao -- 128
 * prova que o LOTE foi processado, nao prova nada sobre o resultado do evento individual, e nunca
 * deve virar REJEITADO por omissao (achado de revisao, 12-08-2026: classificar ausencia de
 * infEvento como rejeicao inventaria uma rejeicao fiscal que a SEFAZ nunca emitiu).
 *
 *   135 -> REGISTRADO           Evento registrado e vinculado a NF-e -- cancelamento efetivo.
 *   155 -> REGISTRADO           Cancelamento homologado fora do prazo -- efetivo, so com motivo
 *                                diferente (fora_do_prazo=true). Nao decide aqui se o prazo era ou
 *                                nao valido -- a SEFAZ ja decidiu isso ao devolver 155 em vez de
 *                                rejeitar; nunca reimplementar essa regra localmente (ver nota
 *                                sobre Ajuste SINIEF 13/2025, tratado no gate de contingencia).
 *   136 -> PENDENTE_CONFIRMACAO Evento registrado mas NAO vinculado a NF-e -- anomalo, nao e
 *                                rejeicao limpa nem sucesso: a SEFAZ guardou o evento mas nao
 *                                confirma que ele afetou a NF-e que o Borurio acredita autorizada.
 *                                Preserva cStat/xMotivo, nenhum efeito local, vai para
 *                                reconciliacao.
 *   573 -> PENDENTE_CONFIRMACAO Rejeicao: Duplicidade de Evento -- prova que ja existe um evento
 *                                registrado para esta identidade (chNFe+tpEvento+nSeqEvento), mas
 *                                NAO prova sozinho se aquele evento original foi homologado. Nunca
 *                                retransmitir; sempre reconciliar via Consulta Situacao.
 *   outros -> REJEITADO         Rejeicoes fiscais comuns (schema, assinatura, protocolo
 *                                incorreto etc.) -- sem efeito, terminal.
 */
@Component
public class NfeEventoClassificador {

    public record Resultado(String estado, boolean foraDoPrazo) {
        static Resultado registrado(boolean foraDoPrazo) {
            return new Resultado(NfeEvento.Estados.REGISTRADO, foraDoPrazo);
        }
        static Resultado pendenteConfirmacao() {
            return new Resultado(NfeEvento.Estados.PENDENTE_CONFIRMACAO, false);
        }
        static Resultado rejeitado() {
            return new Resultado(NfeEvento.Estados.REJEITADO, false);
        }
    }

    /**
     * @param cStatInfEvento cStat individual REAL do evento -- nunca null. O chamador decide
     *                       ANTES de invocar este metodo se ha um cStat individual disponivel;
     *                       chamar isto sem um cStat real e erro de uso, nao um caso de negocio.
     */
    public Resultado classificar(int cStatInfEvento) {
        return switch (cStatInfEvento) {
            case 135 -> Resultado.registrado(false);
            case 155 -> Resultado.registrado(true);
            case 136, 573 -> Resultado.pendenteConfirmacao();
            default -> Resultado.rejeitado();
        };
    }
}
