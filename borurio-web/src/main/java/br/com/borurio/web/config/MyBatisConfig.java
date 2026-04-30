package br.com.borurio.web.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackages = {
        "br.com.borurio.app.mapper",
        "br.com.borurio.fiscal.mapper"
})
public class MyBatisConfig {
}
