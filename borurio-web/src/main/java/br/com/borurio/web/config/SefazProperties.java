package br.com.borurio.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sefaz.urls")
public class SefazProperties {

    private String autorizacao;
    private String retorno;
    private String consulta;
    private String status;
    private String inutilizacao;
    private String recepcaoEvento;
}
