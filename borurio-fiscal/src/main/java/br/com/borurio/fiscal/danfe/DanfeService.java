package br.com.borurio.fiscal.danfe;

/** Gera o DANFE em PDF a partir da chave de acesso da NF-e. */
public interface DanfeService {

    /**
     * Retorna o PDF do DANFE como array de bytes.
     *
     * @throws java.util.NoSuchElementException se não houver NF-e com a chave informada
     * @throws IllegalStateException            se o XML ainda não estiver disponível ou se a geração falhar
     */
    byte[] gerarDanfe(String chaveNfe);
}
