package br.com.borurio.web.service;

/**
 * Credenciais do MySQL efêmero/descartável usado por NfeContingenciaConcorrenciaRealMySqlIT —
 * mesmo padrão de Gate3ReconciliacaoTestProperties/P03LockOrderTestProperties: lidas
 * exclusivamente de variáveis de ambiente, nenhum default, nenhuma credencial fixa no código.
 *
 * Variáveis exigidas (definir só na sessão que for rodar este teste, nunca versionar):
 *   CONTINGENCIA_DB_URL, CONTINGENCIA_DB_USERNAME, CONTINGENCIA_DB_PASSWORD
 */
final class NfeContingenciaConcorrenciaTestProperties {

    private NfeContingenciaConcorrenciaTestProperties() {}

    static String dbUrl()      { return required("CONTINGENCIA_DB_URL"); }
    static String dbUsername() { return required("CONTINGENCIA_DB_USERNAME"); }
    static String dbPassword() { return required("CONTINGENCIA_DB_PASSWORD"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para NfeContingenciaConcorrenciaRealMySqlIT: " + envVar
                            + ". Defina-a na sessão antes de rodar este teste (MySQL efêmero/descartável).");
        }
        return value;
    }
}
