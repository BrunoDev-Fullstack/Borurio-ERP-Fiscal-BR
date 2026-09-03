package br.com.borurio.web.service;

/**
 * Credenciais do MySQL efêmero/descartável usado por NfeEmissaoLockOrderRealMySqlIT — mesmo
 * padrão de Gate1AuditTestProperties (borurio-fiscal) e GateBTestProperties: lidas exclusivamente
 * de variáveis de ambiente, nenhum default, nenhuma credencial fixa no código.
 *
 * Variáveis exigidas (definir só na sessão que for rodar este teste, nunca versionar):
 *   P03_DB_URL, P03_DB_USERNAME, P03_DB_PASSWORD
 */
final class P03LockOrderTestProperties {

    private P03LockOrderTestProperties() {}

    static String dbUrl()      { return required("P03_DB_URL"); }
    static String dbUsername() { return required("P03_DB_USERNAME"); }
    static String dbPassword() { return required("P03_DB_PASSWORD"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para NfeEmissaoLockOrderRealMySqlIT: " + envVar
                            + ". Defina-a na sessão antes de rodar este teste (MySQL efêmero/descartável).");
        }
        return value;
    }
}
