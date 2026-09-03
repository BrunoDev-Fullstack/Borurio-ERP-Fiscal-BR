package br.com.borurio.web.service;

/**
 * Credenciais do MySQL efêmero/descartável usado por Gate3ReconciliacaoRealMySqlIT — mesmo padrão
 * de P03LockOrderTestProperties: lidas exclusivamente de variáveis de ambiente, nenhum default,
 * nenhuma credencial fixa no código.
 *
 * Variáveis exigidas (definir só na sessão que for rodar este teste, nunca versionar):
 *   GATE3_DB_URL, GATE3_DB_USERNAME, GATE3_DB_PASSWORD
 */
final class Gate3ReconciliacaoTestProperties {

    private Gate3ReconciliacaoTestProperties() {}

    static String dbUrl()      { return required("GATE3_DB_URL"); }
    static String dbUsername() { return required("GATE3_DB_USERNAME"); }
    static String dbPassword() { return required("GATE3_DB_PASSWORD"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para Gate3ReconciliacaoRealMySqlIT: " + envVar
                            + ". Defina-a na sessão antes de rodar este teste (MySQL efêmero/descartável).");
        }
        return value;
    }
}
