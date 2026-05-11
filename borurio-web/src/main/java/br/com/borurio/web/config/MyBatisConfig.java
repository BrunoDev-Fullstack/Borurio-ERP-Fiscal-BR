package br.com.borurio.web.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnBean(DataSource.class)
@MapperScan(basePackages = {
        "br.com.borurio.app.mapper",
        "br.com.borurio.fiscal.mapper"
})
public class MyBatisConfig {
}
