package br.com.borurio.web.controller.fiscal;

import br.com.borurio.fiscal.danfe.DanfeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @Operation(
            summary = "Gera e retorna o DANFE em PDF para a NF-e informada",
            description = "Requer `chave` com exatamente **44 dígitos** — retorna HTTP 400 se o comprimento for diferente. " +
                          "Resposta: `Content-Type: application/pdf` com `Content-Disposition: attachment; filename=danfe-{chave}.pdf`. " +
                          "Em HOM (`tpAmb=2`), o PDF exibe marca d'água diagonal **'SEM VALOR FISCAL'** — comportamento correto e obrigatório. " +
                          "A NF-e deve ter sido transmitida previamente via `POST /api/app/pedidos/{id}/emitir` para que o XML esteja disponível. " +
                          "O DANFE pode ser gerado mesmo quando `cStat=225` (HOM/SP), desde que `chaveNfe` de 44 dígitos tenha sido retornada."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "PDF gerado com sucesso — Content-Type: application/pdf"),
            @ApiResponse(responseCode = "400", description = "Chave com comprimento diferente de 44 dígitos"),
            @ApiResponse(responseCode = "401", description = "Token JWT ausente ou expirado"),
            @ApiResponse(responseCode = "404", description = "NF-e não encontrada na tabela nfe_documento"),
            @ApiResponse(responseCode = "422", description = "XML da NF-e não disponível para geração do PDF"),
            @ApiResponse(responseCode = "500", description = "Falha interna na geração do PDF")
    })
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
