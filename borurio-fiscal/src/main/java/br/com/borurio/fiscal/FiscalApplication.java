package br.com.borurio.fiscal;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Classe principal do módulo fiscal do ERP Borurio Brasil.
 *
 * Responsável por inicializar o contexto Spring Boot,
 * registrar os mappers MyBatis e garantir o carregamento
 * dos serviços fiscais (NF-e, SEFAZ, certificados e logs).
 *
 * Boas práticas aplicadas:
 * - Padrão DevSecOps: configurações externas via YAML e .env.
 * - Scan abrangente para detecção de beans (serviços e componentes).
 * - Registro explícito dos mappers MyBatis.
 * - Compatível com Spring Boot 3.3.x e Java 17+.
 *
 * Parâmetros principais (application-dev.yml):
 * - Datasources, Redis e Certificado A1 (.pfx).
 * - Perfis: dev, prd.
 */
@SpringBootApplication(scanBasePackages = "br.com.borurio")
@MapperScan(basePackages = "br.com.borurio.fiscal.mapper")
public class FiscalApplication {

    /**
     * Método principal de inicialização do módulo fiscal.
     * Responsável por iniciar o contexto Spring Boot e
     * carregar automaticamente todos os beans do pacote base.
     *
     * @param args argumentos de linha de comando padrão.
     */
    public static void main(String[] args) {
        SpringApplication.run(FiscalApplication.class, args);
    }
}
