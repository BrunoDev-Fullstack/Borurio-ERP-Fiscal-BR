package br.com.borurio.fiscal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configurações de endpoints da SEFAZ para integração NF-e.
 *
 * As URLs são carregadas a partir do application.yml utilizando
 * o prefixo "sefaz.urls".
 *
 * Exemplo de configuração no application-hom.yml:
 *
 * sefaz:
 *   urls:
 *     status: https://homologacao.nfe.fazenda.sp.gov.br/ws/nfestatusservico4.asmx
 *     autorizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeautorizacao4.asmx
 *     retorno: https://homologacao.nfe.fazenda.sp.gov.br/ws/nferetautorizacao4.asmx
 *     consulta: https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeconsulta4.asmx
 *     inutilizacao: https://homologacao.nfe.fazenda.sp.gov.br/ws/nfeinutilizacao4.asmx
 *     recepcaoEvento: https://homologacao.nfe.fazenda.sp.gov.br/ws/recepcaoevento4.asmx
 */
@Configuration
@ConfigurationProperties(prefix = "sefaz.urls")
public class SefazProperties {

    /**
     * Endpoint de autorização de NF-e.
     */
    private String autorizacao;

    /**
     * Endpoint de retorno do lote enviado.
     */
    private String retorno;

    /**
     * Endpoint de consulta de NF-e pela chave.
     */
    private String consulta;

    /**
     * Endpoint de status do serviço SEFAZ.
     */
    private String status;

    /**
     * Endpoint de inutilização de numeração.
     */
    private String inutilizacao;

    /**
     * Endpoint de recepção de eventos (cancelamento, carta de correção etc).
     */
    private String recepcaoEvento;

    public String getAutorizacao() {
        return autorizacao;
    }

    public void setAutorizacao(String autorizacao) {
        this.autorizacao = autorizacao;
    }

    public String getRetorno() {
        return retorno;
    }

    public void setRetorno(String retorno) {
        this.retorno = retorno;
    }

    public String getConsulta() {
        return consulta;
    }

    public void setConsulta(String consulta) {
        this.consulta = consulta;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInutilizacao() {
        return inutilizacao;
    }

    public void setInutilizacao(String inutilizacao) {
        this.inutilizacao = inutilizacao;
    }

    public String getRecepcaoEvento() {
        return recepcaoEvento;
    }

    public void setRecepcaoEvento(String recepcaoEvento) {
        this.recepcaoEvento = recepcaoEvento;
    }
}