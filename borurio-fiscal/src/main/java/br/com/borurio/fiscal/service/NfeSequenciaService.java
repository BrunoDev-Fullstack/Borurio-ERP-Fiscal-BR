package br.com.borurio.fiscal.service;

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
}
