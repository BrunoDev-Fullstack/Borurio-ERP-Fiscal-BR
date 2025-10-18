package br.com.borurio.fiscal.config;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import javax.sql.DataSource;

/**
 * Configuração de teste isolado para o MyBatis.
 * Esta classe cria um datasource H2 em memória apenas para execução dos testes unitários
 * que dependem do contexto MyBatis, sem necessidade de conexão com o MySQL real.
 *
 * Local de uso: testes como NfeLogServiceTest e NfeAuthorizeServiceTest.
 */
@Configuration
@MapperScan(basePackages = "br.com.borurio.fiscal.mapper")
public class TestMyBatisConfig {

    /**
     * Cria o datasource em memória (H2) usado somente nos testes.
     * O schema será inicializado via script Flyway ou manualmente pelos testes.
     */
    @Bean
    public DataSource dataSource() {
        return DataSourceBuilder.create()
                .url("jdbc:h2:mem:borurio_fiscal_test;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }

    /**
     * Cria a fábrica de sessões MyBatis para o contexto de teste.
     */
    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        return factoryBean.getObject();
    }

    /**
     * Gerenciador de transações para o datasource de teste.
     */
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
