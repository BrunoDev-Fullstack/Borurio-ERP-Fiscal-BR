package br.com.borurio.fiscal.service;

/**
 * Credenciais do MySQL efêmero/descartável usado por NfeSequenciaGateRealMySqlIT, lidas
 * exclusivamente de variáveis de ambiente — mesmo padrão de GateBTestProperties (borurio-web).
 * Nenhum default, nenhuma credencial fixa no código.
 *
 * Variáveis exigidas (definir só na sessão que for rodar este teste, nunca versionar):
 *   GATE1_DB_URL, GATE1_DB_USERNAME, GATE1_DB_PASSWORD
 */
final class Gate1AuditTestProperties {

    private Gate1AuditTestProperties() {}

    static String dbUrl()      { return required("GATE1_DB_URL"); }
    static String dbUsername() { return required("GATE1_DB_USERNAME"); }
    static String dbPassword() { return required("GATE1_DB_PASSWORD"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para NfeSequenciaGateRealMySqlIT: " + envVar
                            + ". Defina-a na sessão antes de rodar este teste (MySQL efêmero/descartável).");
        }
        return value;
    }
}
