package br.com.borurio.web.exception;

import br.com.borurio.app.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cobre o envelope de erro padronizado (Requisito 4): code, message, errorCode, retryable
 * e requestId — pra que o OMS consiga decidir programaticamente entre retry e correção manual.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    void sefazTimeout_retryableTrue_http503() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(BusinessException.sefazTimeout());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("SEFAZ_TIMEOUT", resp.getBody().get("errorCode"));
        assertEquals(true, resp.getBody().get("retryable"));
    }

    @Test
    void sefazUnavailable_retryableTrue_http503() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(BusinessException.sefazUnavailable());

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("SEFAZ_UNAVAILABLE", resp.getBody().get("errorCode"));
        assertEquals(true, resp.getBody().get("retryable"));
    }

    @Test
    void xmlSchemaInvalid_retryableFalse_http422() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(
                BusinessException.xmlSchemaInvalid("enderEmit incompleto"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("XML_SCHEMA_INVALID", resp.getBody().get("errorCode"));
        assertEquals(false, resp.getBody().get("retryable"));
    }

    @Test
    void emitterAddressIncomplete_retryableFalse_http422() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(
                BusinessException.emitterAddressIncomplete());

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("EMITTER_ADDRESS_INCOMPLETE", resp.getBody().get("errorCode"));
        assertEquals(false, resp.getBody().get("retryable"));
        assertEquals("Cadastro do emitente incompleto.", resp.getBody().get("message"));
    }

    @Test
    void sefazRejected_exposeCStatEXMotivoEmData() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(
                BusinessException.sefazRejected(225, "Rejeição: Falha no Schema XML do lote de NFe"));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("SEFAZ_REJECTED", resp.getBody().get("errorCode"));
        assertEquals(false, resp.getBody().get("retryable"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertEquals(225, data.get("cStat"));
        assertEquals("Rejeição: Falha no Schema XML do lote de NFe", data.get("xMotivo"));
    }

    @Test
    void insufficientStock_retryableFalse_comportamentoExistentePreservado() {
        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(
                BusinessException.insufficientStock("Produto X", new BigDecimal("0"), new BigDecimal("5")));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, resp.getStatusCode());
        assertEquals("INSUFFICIENT_STOCK", resp.getBody().get("errorCode"));
        assertEquals(false, resp.getBody().get("retryable"));
    }

    @Test
    void erroInesperado_retryableFalse_http500() {
        ResponseEntity<Map<String, Object>> resp = handler.handleGeneric(new RuntimeException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("Erro interno do servidor", resp.getBody().get("message"));
        assertEquals(false, resp.getBody().get("retryable"));
    }

    @Test
    void requestId_presenteQuandoMdcConfigurado() {
        MDC.put("requestId", "abc-123");

        ResponseEntity<Map<String, Object>> resp = handler.handleBusiness(BusinessException.sefazTimeout());

        assertEquals("abc-123", resp.getBody().get("requestId"));
    }
}
