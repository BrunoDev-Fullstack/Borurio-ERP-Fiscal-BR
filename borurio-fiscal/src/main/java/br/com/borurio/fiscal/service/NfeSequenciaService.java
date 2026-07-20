package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;

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
}
