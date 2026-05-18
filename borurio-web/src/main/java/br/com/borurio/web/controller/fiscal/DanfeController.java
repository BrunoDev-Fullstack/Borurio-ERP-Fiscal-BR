package br.com.borurio.web.controller.fiscal;

import br.com.borurio.fiscal.danfe.DanfeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class DanfeController {

    private final DanfeService danfeService;

    public DanfeController(DanfeService danfeService) {
        this.danfeService = danfeService;
    }

    @GetMapping("/{chave}/danfe")
    @Operation(summary = "Gera e retorna o DANFE em PDF para a NF-e informada")
    public ResponseEntity<byte[]> getDanfe(@PathVariable String chave) {
        log.info("[DANFE] Requisição recebida | chave={}", chave);

        if (chave == null || chave.length() != 44) {
            return ResponseEntity.badRequest().build();
        }

        try {
            byte[] pdf = danfeService.gerarDanfe(chave);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "danfe-" + chave + ".pdf");
            headers.setContentLength(pdf.length);

            return ResponseEntity.ok().headers(headers).body(pdf);

        } catch (NoSuchElementException e) {
            log.warn("[DANFE] NF-e não encontrada | chave={}", chave);
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("[DANFE] Estado inválido | chave={} | erro={}", chave, e.getMessage());
            return ResponseEntity.unprocessableEntity().build();
        } catch (Exception e) {
            log.error("[DANFE] Falha ao gerar PDF | chave={} | erro={}", chave, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
