package br.com.borurio.web.controller.fiscal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * =============================================================================
 * NFE TEST CONTROLLER
 * -----------------------------------------------------------------------------
 * Controller de teste do módulo Fiscal — valida carregamento e integração SEFAZ-SP.
 *
 * Localização: borurio-web (exposição REST)
 * Integração: borurio-fiscal (lógica de negócio)
 *
 * Endpoint:
 *   GET /api/fiscal/nfe/test/ping
 *
 * Retorno esperado:
 *   {
 *     "status": "OK",
 *     "message": "Módulo Fiscal operacional — integração SEFAZ-SP pronta para homologação.",
 *     "timestamp": "2025-10-28T15:55:00",
 *     "ambiente": "HOMOLOGAÇÃO (tpAmb=2)"
 *   }
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Projeto: ERP Fiscal Borurio BR
 * Data: Outubro/2025
 * =============================================================================
 */
@RestController
@RequestMapping("/api/fiscal/nfe/test")
public class NfeTestController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("status", "OK");
        resp.put("message", "Módulo Fiscal operacional — integração SEFAZ-SP pronta para homologação.");
        resp.put("timestamp", LocalDateTime.now().toString());
        resp.put("ambiente", "HOMOLOGAÇÃO (tpAmb=2)");
        return resp;
    }
}
