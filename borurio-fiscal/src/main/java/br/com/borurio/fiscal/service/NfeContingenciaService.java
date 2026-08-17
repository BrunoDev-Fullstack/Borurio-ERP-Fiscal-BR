package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeEmissao;

import java.time.OffsetDateTime;

/**
 * Fase 1 SVC (17-08-2026, persistência/ciclo de substituição — ver
 * Checkpoint_Interno_Semanal_2026-08-14.md seção 3). Fora de escopo desta fase: geração de XML/
 * chave SVC, transporte real à SEFAZ/SVC, endpoint OMS, HOM.
 *
 * Construtor recebe só {@code NfeSequenciaService}/{@code NfeEmissaoMapper} — impossível receber
 * {@code PedidoMapper}/{@code EstoqueService} (não existem em {@code borurio-fiscal}, que não
 * depende de {@code borurio-app}) — impossibilidade estrutural, não convenção.
 */
public interface NfeContingenciaService {

    /**
     * Abre contingência sobre o ciclo ativo de {@code emissaoNormalId} (Caminho B — NORMAL
     * {@code TRANSMITIDO}/{@code PENDENTE_CONFIRMACAO}, chave já congelada): consolida o número da
     * NORMAL como definitivamente não-reutilizável, cria uma nova emissão (a "filha") com o
     * próximo número da sequência, e move o gate da série NORMAL→filha via CAS explícito. A linha
     * NORMAL nunca é apagada nem reescrita — {@code emissaoOrigemId} na filha é a prova durável de
     * que ela foi substituída, sobrevivendo mesmo depois que o ciclo ativo (a própria filha)
     * também terminar.
     *
     * Caminho A (NORMAL ainda {@code RESERVADO}, troca {@code tpEmis}/{@code autorizadorDestino}
     * na mesma linha) **não é implementado nesta fase** — ver Fase 2.
     *
     * @param tpEmisContingencia {@link NfeEmissao.TpEmis#SVC_AN} ou {@link NfeEmissao.TpEmis#SVC_RS}
     *        — {@code autorizadorDestino} é sempre derivado internamente, nunca recebido separado.
     * @param xJust justificativa da entrada em contingência — normalizada via {@code trim()},
     *        15–256 caracteres no valor já normalizado.
     * @param dhCont instante+offset reais de abertura da contingência — o cálculo do offset
     *        correto por UF/empresa é responsabilidade de quem chama este método (Fase 2/3); este
     *        método só preserva o valor recebido, nunca o recalcula.
     * @return a emissão filha recém-criada (id gerado, estado {@code RESERVADO}).
     * @throws IllegalArgumentException se algum parâmetro for inválido (tpEmis fora do domínio
     *         suportado, xJust fora de 15–256 chars após trim, dhCont nulo).
     * @throws br.com.borurio.fiscal.exception.ContingenciaInvalidaException se a emissão não for o
     *         ciclo ativo da série, já tiver sido substituída, estiver em {@code RESERVADO}
     *         (Caminho A) ou em estado terminal/{@code AGUARDANDO_CORRECAO}.
     */
    NfeEmissao abrirContingencia(Long emissaoNormalId, String tpEmisContingencia, String xJust, OffsetDateTime dhCont);
}
