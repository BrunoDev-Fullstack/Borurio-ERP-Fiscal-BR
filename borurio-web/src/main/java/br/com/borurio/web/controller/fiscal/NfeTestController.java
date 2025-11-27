package br.com.borurio.web.controller.fiscal;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * =============================================================================
 * CONTROLADOR FISCAL – TESTE & STATUS (NF-e)
 * =============================================================================
 * Objetivo:
 *   Endpoints de teste e diagnóstico para validar que o módulo fiscal
 *   está operacional e pronto para integração com a SEFAZ-SP.
 *
 * Padrão de retorno:
 *   Utiliza Result<T> do módulo borurio-core.
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
public class NfeTestController {

    /**
     * TESTE LOCAL — confirma que o módulo fiscal está acessível.
     */
    @GetMapping("/test/ping")
    public Result<Object> ping() {

        log.info("[NF-e TEST] Ping fiscal solicitado.");

        return ResultUtil.success(buildMetadata(
                "Módulo Fiscal operacional — pronto para integração com SEFAZ-SP.",
                "PING"
        ));
    }

    /**
     * STATUS (STUB) — indica se o serviço SEFAZ está acessível.
     * Futuro: integrar com NfeStatusServiceImpl.
     */
    @GetMapping("/status")
    public Result<Object> statusSefaz() {

        log.info("[NF-e STATUS] Consulta de status SEFAZ-SP solicitada (STUB).");

        return ResultUtil.success(buildMetadata(
                "Serviço SEFAZ-SP disponível (STUB).",
                "STATUS_SEFAZ"
        ));
    }

    /**
     * METADADOS retornados para fins de diagnóstico.
     */
    private Object buildMetadata(String message, String operation) {
        return new Object() {
            public final String mensagem = message;
            public final String operacao = operation;
            public final String ambiente = "HOMOLOGAÇÃO (tpAmb=2)";
            public final String timestamp = LocalDateTime.now().toString();
        };
    }
}
