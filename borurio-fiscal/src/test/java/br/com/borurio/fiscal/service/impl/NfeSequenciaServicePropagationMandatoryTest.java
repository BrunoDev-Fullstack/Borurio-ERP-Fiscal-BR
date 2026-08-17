package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Fase 1 SVC (17-08-2026) — prova real (contexto Spring, com o AOP proxy transacional de
 * verdade; um teste Mockito puro NUNCA exercitaria isso, já que {@code new
 * NfeSequenciaServiceImpl(mapper)} não passa pelo proxy do Spring) de que
 * {@code consolidarNumeroParaContingencia}/{@code substituirGateParaContingencia} são
 * {@code Propagation.MANDATORY}: chamá-los fora de uma transação externa já aberta lança
 * {@link IllegalTransactionStateException}.
 *
 * {@code borurio-fiscal} não depende de {@code spring-jdbc} (o {@code PlatformTransactionManager}
 * real de produção, {@code DataSourceTransactionManager}, é montado só em {@code borurio-web}) —
 * a checagem de propagação MANDATORY, porém, é genérica em
 * {@link AbstractPlatformTransactionManager#getTransaction}, comum a qualquer implementação. Um
 * gerenciador no-op mínimo (sem acesso a nenhum recurso real) já é suficiente pra provar o
 * comportamento, sem precisar puxar spring-jdbc só para este teste.
 *
 * O caminho positivo (chamado de dentro da transação já aberta por
 * {@code NfeContingenciaService.abrirContingencia}) é exercitado pelos testes de
 * {@code NfeContingenciaServiceImplTest} e por {@code NfeContingenciaConcorrenciaRealMySqlIT}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NfeSequenciaServicePropagationMandatoryTest.Config.class)
class NfeSequenciaServicePropagationMandatoryTest {

    static class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // sem recurso real -- suficiente pra exercitar a checagem de propagação genérica
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new NoopTransactionManager();
        }

        @Bean
        NfeSequenciaMapper nfeSequenciaMapper() {
            return mock(NfeSequenciaMapper.class);
        }

        @Bean
        NfeSequenciaService nfeSequenciaService(NfeSequenciaMapper mapper) {
            return new NfeSequenciaServiceImpl(mapper);
        }
    }

    @Autowired
    NfeSequenciaService service;

    @Test
    @DisplayName("consolidarNumeroParaContingencia sem transação externa lança IllegalTransactionStateException")
    void consolidarNumeroParaContingencia_semTransacaoExterna_lancaIllegalTransactionState() {
        assertThrows(IllegalTransactionStateException.class,
                () -> service.consolidarNumeroParaContingencia("22418179000134", "1", 1));
    }

    @Test
    @DisplayName("substituirGateParaContingencia sem transação externa lança IllegalTransactionStateException")
    void substituirGateParaContingencia_semTransacaoExterna_lancaIllegalTransactionState() {
        assertThrows(IllegalTransactionStateException.class,
                () -> service.substituirGateParaContingencia("22418179000134", "1", 501L, 900L));
    }
}
