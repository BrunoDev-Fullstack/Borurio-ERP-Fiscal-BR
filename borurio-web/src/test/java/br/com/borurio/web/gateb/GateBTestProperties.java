package br.com.borurio.web.gateb;

/**
 * Credenciais do Gate B lidas exclusivamente de variáveis de ambiente — nunca de valor fixo no
 * código. Nenhum default, nenhum fallback. Falha rápido citando só o NOME da variável ausente,
 * nunca o valor (mesmo em erro, nada sensível vai para stack trace/log).
 *
 * Variáveis exigidas (definir só na sessão que for rodar os testes Gate B, nunca versionar):
 *   GATEB_DB_URL                — string de conexão JDBC completa do MySQL descartável
 *   GATEB_DB_USERNAME
 *   GATEB_DB_PASSWORD
 *   GATEB_CERT_PATH             — caminho do certificado sintético (fora do repositório)
 *   GATEB_CERT_PASSWORD
 *   GATEB_CERT_ENCRYPTION_KEY   — só exigida por GateBHttpIT (fluxo que decodifica certificado OMS)
 */
final class GateBTestProperties {

    private GateBTestProperties() {}

    static String dbUrl()             { return required("GATEB_DB_URL"); }
    static String dbUsername()        { return required("GATEB_DB_USERNAME"); }
    static String dbPassword()        { return required("GATEB_DB_PASSWORD"); }
    static String certPath()          { return required("GATEB_CERT_PATH"); }
    static String certPassword()      { return required("GATEB_CERT_PASSWORD"); }
    static String certEncryptionKey() { return required("GATEB_CERT_ENCRYPTION_KEY"); }

    private static String required(String envVar) {
        String value = System.getenv(envVar);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Variável de ambiente obrigatória ausente para o Gate B: " + envVar
                            + ". Defina-a na sessão do PowerShell antes de rodar estes testes.");
        }
        return value;
    }
}
