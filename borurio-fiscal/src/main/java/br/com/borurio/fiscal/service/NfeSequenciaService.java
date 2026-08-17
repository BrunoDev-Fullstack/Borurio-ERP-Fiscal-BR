package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequencia;

public interface NfeSequenciaService {

    /**
     * Retorna o próximo número de NF-e para o par (cnpjEmitente, serie),
     * incrementando o contador de forma atômica.
     * Se não existir registro para a série, inicializa em 1.
     */
    int proximoNumero(String cnpjEmitente, String serie);

    /**
     * Configura, pela primeira vez, a sequência de um CNPJ+série com um número já conhecido —
     * uso principal: onboarding de uma empresa que já emitiu NF-e em outro sistema antes de
     * passar a emitir pelo Borurio. Depois desta chamada, a próxima alocação via
     * proximoNumero() será ultimoNumeroConhecido + 1.
     *
     * Regra estrita — cobre só a PRIMEIRA configuração de uma sequência nova:
     *   sequência inexistente        → cria com o valor informado
     *   sequência já igual ao valor  → idempotente (sucesso, nada é escrito)
     *   sequência já existe diferente (maior OU menor) → IllegalStateException, nada é escrito
     *
     * Nunca avança nem regride uma sequência já ativa — isso exigiria decisão administrativa
     * separada e auditável, fora do escopo deste método. Uma sequência com número diferente do
     * baseline informado significa que ela já está em uso; sobrescrever isso silenciosamente,
     * pra cima ou pra baixo, arrisca reemitir ou pular números fiscais.
     *
     * Protegida pela mesma transação SERIALIZABLE + SELECT FOR UPDATE de proximoNumero() —
     * segura contra chamada concorrente, inclusive concorrendo com proximoNumero() no mesmo
     * CNPJ+série.
     *
     * @throws IllegalArgumentException se cnpjEmitente/serie forem nulos ou vazios, ou se
     *         ultimoNumeroConhecido for negativo
     * @throws IllegalStateException se a sequência já existir com valor diferente do informado
     */
    void inicializarBaseline(String cnpjEmitente, String serie, int ultimoNumeroConhecido);

    /**
     * Atualiza a sequência de (cnpjEmitente, serie) para uma sincronização recorrente vinda da
     * OMS — diferente de {@link #inicializarBaseline}, que só cobre a primeira configuração.
     *
     * Regra (proximoNumero é o próximo nNF que a OMS diz que o Borurio deve usar):
     *   sequência inexistente                              → cria com ultimoNumero = proximoNumero - 1
     *   proximoNumero - 1 == ultimoNumero atual             → idempotente, nada muda, aplicado=false
     *   proximoNumero - 1 >  ultimoNumero atual             → avança (aceito), aplicado=true
     *   proximoNumero - 1 <  ultimoNumero atual             → IllegalStateException (regressão rejeitada)
     *
     * Mesma transação SERIALIZABLE + SELECT FOR UPDATE de proximoNumero()/inicializarBaseline().
     * Só cobre a tabela nfe_sequencia — não atualiza Empresa.serieNfePadrao nem grava auditoria;
     * isso é responsabilidade do orquestrador em borurio-web, que também precisa bloquear a
     * linha de Empresa (fora do escopo deste service, que não conhece a entidade Empresa).
     *
     * @throws IllegalArgumentException se cnpjEmitente/serie forem nulos/vazios, ou se
     *         proximoNumero for menor que 1
     * @throws IllegalStateException se proximoNumero representar uma regressão de numeração
     */
    AtualizacaoSequenciaResultado atualizarSequencia(String cnpjEmitente, String serie, int proximoNumero);

    // -------------------------------------------------------------------------
    // Gate 1 — ciclo do nNF (NfeEmissaoService). Estes métodos NÃO incrementam
    // ultimo_numero na reserva — só na resolução terminal (consumirNumero), diferente de
    // proximoNumero() acima, que permanece intocado para o caminho de fallback legado.
    // -------------------------------------------------------------------------

    /**
     * Bloqueia (FOR UPDATE) a linha de (cnpjEmitente, serie), criando-a com ultimoNumero=0 se
     * ainda não existir. Base para peekProximoNumero() e para o chamador ler emissaoAtivaId
     * (estado do gate) dentro da mesma transação.
     *
     * @throws IllegalArgumentException se cnpjEmitente/serie forem nulos ou vazios
     */
    NfeSequencia buscarOuCriarParaAtualizar(String cnpjEmitente, String serie);

    /**
     * Mesmo lock (FOR UPDATE) de buscarOuCriarParaAtualizar(), mas NUNCA cria a linha — devolve
     * null se a sequência ainda não existir. Uso: checagens somente-leitura de gate
     * (emissaoAtivaId) num CNPJ+série que o chamador não pretende necessariamente escrever (ex.:
     * FiscalNumberingService checando a série que está sendo abandonada numa troca de série —
     * criar uma linha ali só para ler seria um efeito colateral indevido).
     */
    NfeSequencia buscarSeExistirParaAtualizar(String cnpjEmitente, String serie);

    /**
     * Devolve o próximo número candidato (ultimoNumero + 1) SEM persistir — a diferença central
     * para proximoNumero(), que persiste o incremento imediatamente. O candidato só vira
     * definitivo quando consumirNumero() for chamado, na resolução terminal do ciclo.
     */
    int peekProximoNumero(String cnpjEmitente, String serie);

    /** Ocupa o gate da série com o id de uma nfe_emissao — mesma transação de buscarOuCriarParaAtualizar. */
    void ocuparGate(String cnpjEmitente, String serie, Long emissaoAtivaId);

    /** Libera o gate da série (emissao_ativa_id = NULL) — chamado só ao alcançar estado terminal. */
    void liberarGate(String cnpjEmitente, String serie);

    /**
     * Persiste definitivamente o consumo de um número — só deve ser chamado quando o ciclo do
     * nNF chega a um estado terminal (AUTORIZADO ou DENEGADO).
     *
     * @throws IllegalStateException se a sequência não existir, ou se {@code numero} não for
     *         exatamente ultimoNumero + 1 no momento da chamada — proteção contra desvio entre a
     *         máquina de estados de nfe_emissao e o contador real, nunca deve acontecer em uso
     *         normal (o gate impede outra reserva concorrente no meio do caminho).
     */
    void consumirNumero(String cnpjEmitente, String serie, int numero);

    // -------------------------------------------------------------------------
    // Fase 1 SVC (17-08-2026) — persistência/ciclo de substituição, sem transporte. Ambos os
    // métodos abaixo são Propagation.MANDATORY de propósito: a unidade atômica real é
    // "consolidar + reservar nNF da filha + inserir filha + trocar o gate", nunca um passo
    // isolado — chamar qualquer um dos dois sem uma transação externa já aberta (sempre
    // NfeContingenciaService.abrirContingencia) lança IllegalTransactionStateException.
    // -------------------------------------------------------------------------

    /**
     * Consolida o número da NORMAL como definitivamente não-reutilizável, avançando
     * {@code ultimo_numero} até esse valor sem tocar {@code emissao_ativa_id} nem o estado da
     * NORMAL (que continua {@code TRANSMITIDO}/{@code PENDENTE_CONFIRMACAO}, intocado). Mesma
     * checagem estrita {@code numero == ultimoNumero + 1} de {@link #consumirNumero}, mas nunca a
     * reaproveita — operação nomeada e auditável, específica de contingência.
     *
     * @return o {@code ultimoNumero} resultante da consolidação — autoridade formal de onde o
     *         chamador deriva o número da filha ({@code retorno + 1}), nunca de um campo de
     *         {@code nfe_emissao} lido antes do lock.
     * @throws IllegalStateException se a sequência não existir, ou se {@code numero} não for
     *         exatamente {@code ultimoNumero + 1}.
     */
    int consolidarNumeroParaContingencia(String cnpjEmitente, String serie, int numero);

    /**
     * CAS explícito da troca de gate NORMAL→SVC (Caminho B) — nunca um {@link #ocuparGate}
     * genérico. Só troca se {@code emissao_ativa_id} ainda for exatamente
     * {@code emissaoNormalEsperadaId}.
     *
     * @throws IllegalStateException se a troca afetar 0 linhas — o gate mudou entre a validação e
     *         esta chamada (corrida real; nunca deveria acontecer dado o lock já seguro pelo
     *         chamador, mas tratado como falha explícita, nunca como sucesso silencioso).
     */
    void substituirGateParaContingencia(String cnpjEmitente, String serie,
                                         Long emissaoNormalEsperadaId, Long emissaoSvcNovaId);
}
