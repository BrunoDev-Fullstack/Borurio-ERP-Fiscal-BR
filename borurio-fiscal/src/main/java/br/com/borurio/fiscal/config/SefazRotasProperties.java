package br.com.borurio.fiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rotas SEFAZ do autorizador NORMAL, por UF do emitente (Fase 0 do Gate SVC, 14-08-2026).
 *
 * Substitui, para autorização/status/retorno/consulta, o conjunto único e global que
 * {@code NfeTransmitServiceImpl} lia antes desta fase — cada UF precisa da sua própria rota,
 * porque o roteamento fiscal real depende da UF da empresa emitente, nunca de uma configuração
 * global única. Não cobre inutilização/recepção de evento/manifestação — esses continuam em
 * {@link SefazProperties} ({@code sefaz.urls.*}), fora do escopo desta fase.
 *
 * Só existe o autorizador NORMAL nesta fase — nenhuma chave SVC-AN/SVC-RS é lida ainda. UF sem
 * entrada aqui falha fechado em {@link SefazRotaResolver}, nunca cai para SP por omissão.
 */
@Configuration
@ConfigurationProperties(prefix = "sefaz")
public class SefazRotasProperties {

    private Map<String, Rota> rotas = new LinkedHashMap<>();

    public Map<String, Rota> getRotas() {
        return rotas;
    }

    public void setRotas(Map<String, Rota> rotas) {
        this.rotas = rotas;
    }

    public static class Rota {
        private String autorizacao;
        private String retorno;
        private String consulta;
        private String status;

        public String getAutorizacao() { return autorizacao; }
        public void setAutorizacao(String autorizacao) { this.autorizacao = autorizacao; }

        public String getRetorno() { return retorno; }
        public void setRetorno(String retorno) { this.retorno = retorno; }

        public String getConsulta() { return consulta; }
        public void setConsulta(String consulta) { this.consulta = consulta; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }
}
