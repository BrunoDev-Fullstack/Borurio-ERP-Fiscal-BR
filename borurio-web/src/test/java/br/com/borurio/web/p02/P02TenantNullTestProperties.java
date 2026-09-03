package br.com.borurio.web.p02;

/**
 * Credenciais do MySQL EFÊMERO usado por P02TenantNullFailClosedRealMySqlIT — mesmo padrão de
 * GateBTestProperties/P03LockOrderTestProperties/Gate1AuditTestProperties: lidas exclusivamente de
 * variáveis de ambiente, nenhum default, nenhuma credencial fixa no código.
 *
 * Diferente dos outros *RealMySqlIT: este container nasce e morre dentro da própria sessão de
 * teste (docker run isolado, nunca os containers persistentes borurio-mysql-hom/borurio-mysql-dev).
 *
 * Variáveis exigidas (definir só na sessão que for rodar este teste, nunca versionar):
 *   P02_DB_URL, P02_DB_USERNAME, P02_DB_PASSWORD
 */
final class P02TenantNullTestProperties {

    private P02TenantNullTestProperties() {}

    static String dbUrl()      { return required("P02_DB_URL"); }
    static String dbUsername() { return required("P02_DB_USERNAME"); }
    static String dbPassword() { return required("P02_DB_PASSWORD"); }
    static String certPath()   { return required("P02_CERT_PATH"); }
    static String certPassword() { return required("P02_CERT_PASSWORD"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para P02TenantNullFailClosedRealMySqlIT: " + envVar
                            + ". Defina-a na sessão antes de rodar este teste (MySQL efêmero/descartável).");
        }
        return value;
    }
}
