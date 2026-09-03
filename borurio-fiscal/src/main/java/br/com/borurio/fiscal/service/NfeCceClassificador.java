package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import org.springframework.stereotype.Component;

/**
 * Matriz de classificacao do cStat de infEvento para CC-e (evento 110110) -- deliberadamente
 * separada de NfeEventoClassificador (cancelamento, 110111): o cancelamento conhece 155
 * (homologado fora do prazo) como sucesso; isso NAO existe pra CC-e e nunca pode vazar pra essa
 * matriz. CC-e tem seu proprio codigo de rejeicao estrutural (594, limite de sequencia) que o
 * cancelamento nao tem.
 *
 *   135 -> REGISTRADO           Evento registrado e vinculado a NF-e -- CC-e efetiva.
 *   136 -> PENDENTE_CONFIRMACAO Evento registrado mas NAO vinculado -- anomalo, nunca decide
 *                                sozinho, vai para reconciliacao.
 *   573 -> PENDENTE_CONFIRMACAO Rejeicao: Duplicidade de Evento -- prova que ja existe um evento
 *                                registrado para esta identidade, mas nao prova sozinho se e o
 *                                CONTEUDO desta operacao (ver CCE_EVENTO_DIVERGENTE na
 *                                reconciliacao, que compara xCorrecao alem da identidade).
 *   594 -> REJEITADO            Numero de sequencia maior/invalido -- rejeicao estrutural,
 *                                terminal, nunca corrigivel por retry da mesma tentativa.
 *   outros -> REJEITADO         Rejeicoes fiscais comuns.
 */
@Component
public class NfeCceClassificador {

    public record Resultado(String estado) {
        static Resultado registrado() {
            return new Resultado(NfeEventoIdempotencia.Estados.REGISTRADO);
        }
        static Resultado pendenteConfirmacao() {
            return new Resultado(NfeEventoIdempotencia.Estados.PENDENTE_CONFIRMACAO);
        }
        static Resultado rejeitado() {
            return new Resultado(NfeEventoIdempotencia.Estados.REJEITADO);
        }
    }

    public Resultado classificar(int cStatInfEvento) {
        return switch (cStatInfEvento) {
            case 135 -> Resultado.registrado();
            case 136, 573 -> Resultado.pendenteConfirmacao();
            default -> Resultado.rejeitado(); // inclui 594 e qualquer outra rejeicao
        };
    }
}
