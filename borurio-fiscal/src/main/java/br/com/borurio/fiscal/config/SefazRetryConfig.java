package br.com.borurio.fiscal.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.time.Duration;

@Configuration
public class SefazRetryConfig {

    private static final Logger log = LoggerFactory.getLogger(SefazRetryConfig.class);

    @Value("${sefaz.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${sefaz.retry.wait-seconds:2}")
    private int waitSeconds;

    @Bean
    public Retry sefazRetry() {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofSeconds(waitSeconds))
                // Retryar apenas falhas de rede transientes — SSLException indica cert inválido, não retryar
                .retryOnException(e -> {
                    Throwable cause = e instanceof RuntimeException && e.getCause() != null ? e.getCause() : e;
                    return cause instanceof IOException && !(cause instanceof SSLException);
                })
                .build();

        Retry retry = RetryRegistry.of(config).retry("sefaz");

        retry.getEventPublisher()
                .onRetry(evt -> log.warn("[SEFAZ-RETRY] Tentativa {}/{} após falha: {}",
                        evt.getNumberOfRetryAttempts(), maxAttempts, evt.getLastThrowable().getMessage()))
                .onError(evt -> log.error("[SEFAZ-RETRY] Todas as {} tentativas falharam: {}",
                        maxAttempts, evt.getLastThrowable().getMessage()))
                .onSuccess(evt -> {
                    if (evt.getNumberOfRetryAttempts() > 0) {
                        log.info("[SEFAZ-RETRY] Sucesso na tentativa {}",
                                evt.getNumberOfRetryAttempts() + 1);
                    }
                });

        return retry;
    }
}
