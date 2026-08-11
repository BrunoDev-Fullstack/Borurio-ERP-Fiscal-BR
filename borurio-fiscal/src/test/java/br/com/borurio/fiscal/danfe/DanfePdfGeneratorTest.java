package br.com.borurio.fiscal.danfe;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class DanfePdfGeneratorTest {

    private final DanfePdfGenerator generator = new DanfePdfGenerator();

    // -------------------------------------------------------------------------
    // Fixture
    // -------------------------------------------------------------------------

    private DanfeData dadosHom() {
        DanfeData d = new DanfeData();
        d.tpAmb       = "2";
        d.natOp       = "VENDA DE MERCADORIA";
        d.dhEmi       = "2026-05-21T10:30:00-03:00";
        d.nNF         = "1";
        d.serie       = "1";
        d.cStat       = "225";
        d.emitXNome   = "EMPRESA TESTE LTDA";
        d.emitXFant   = "TESTE";
        d.emitCnpj    = "00000000000191";
        d.emitIe      = "123456789";
        d.emitCrt     = "1";
        d.emitXLgr    = "RUA TESTE";
        d.emitNro     = "100";
        d.emitXBairro = "BAIRRO CENTRO";
        d.emitXMun    = "SAO PAULO";
        d.emitUf      = "SP";
        d.emitCep     = "01310100";
        d.destXNome   = "CLIENTE TESTE LTDA";
        d.destCpfCnpj = "11222333000181";
        d.chaveNfe    = "35260500000000000191550010000000011000000013";
        d.vProd       = "91.80";
        d.vNF         = "91.80";

        DanfeData.Item item = new DanfeData.Item();
        item.nItem  = 1;
        item.cProd  = "PROD001";
        item.xProd  = "PRODUTO TESTE UNITARIO";
        item.ncm    = "84713012";
        item.cfop   = "5102";
        item.uCom   = "UN";
        item.qCom   = "2.0000";
        item.vUnCom = "45.90";
        item.vProd  = "91.80";
        item.csosn  = "400";
        d.itens = List.of(item);
        return d;
    }

    private String extrairTextoPagina1(byte[] pdf) {
        try (PdfReader reader = new PdfReader(pdf)) {
            return new PdfTextExtractor(reader).getTextFromPage(1);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // -------------------------------------------------------------------------
    // Testes estruturais
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Gera PDF HOM (tpAmb=2, cStat=225) com bytes válidos")
    void deveGerarPdfHom() {
        byte[] pdf = generator.gerar(dadosHom());

        assertNotNull(pdf);
        assertTrue(pdf.length > 1_000, "PDF deve ter mais de 1kB");
        // Assinatura PDF válida (%PDF-)
        assertEquals('%', (char) pdf[0]);
        assertEquals('P', (char) pdf[1]);
        assertEquals('D', (char) pdf[2]);
        assertEquals('F', (char) pdf[3]);
    }

    @Test
    @DisplayName("Gera PDF PRD (tpAmb=1) autorizado (cStat=100) com nProt")
    void deveGerarPdfPrdAutorizado() {
        DanfeData d = dadosHom();
        d.tpAmb    = "1";
        d.cStat    = "100";
        d.nProt    = "135260000000001";
        d.dhRecbto = "2026-05-21T10:31:00-03:00";

        byte[] pdf = generator.gerar(d);

        assertNotNull(pdf);
        assertTrue(pdf.length > 1_000);
    }

    @Test
    @DisplayName("cStat=100 com nProt exibe PROTOCOLO DE AUTORIZAÇÃO DE USO")
    void deveExibirProtocoloAutorizacaoParaCStat100() {
        DanfeData d = dadosHom();
        d.tpAmb    = "1";
        d.cStat    = "100";
        d.nProt    = "135260000000001";
        d.dhRecbto = "2026-05-21T10:31:00-03:00";

        String texto = extrairTextoPagina1(generator.gerar(d));

        assertTrue(texto.contains("PROTOCOLO DE AUTORIZAÇÃO DE USO"));
        assertTrue(texto.contains("135260000000001"));
    }

    @Test
    @DisplayName("cStat=150 (autorizado fora do prazo) com nProt exibe PROTOCOLO DE AUTORIZAÇÃO DE USO")
    void deveExibirProtocoloAutorizacaoParaCStat150() {
        DanfeData d = dadosHom();
        d.tpAmb    = "1";
        d.cStat    = "150";
        d.nProt    = "135260000000002";
        d.dhRecbto = "2026-05-21T10:31:00-03:00";

        String texto = extrairTextoPagina1(generator.gerar(d));

        assertTrue(texto.contains("PROTOCOLO DE AUTORIZAÇÃO DE USO"));
        assertTrue(texto.contains("135260000000002"));
    }

    @Test
    @DisplayName("cStat=225 (rejeitado, sem protocolo) mantém comportamento anterior — nunca exibe PROTOCOLO DE AUTORIZAÇÃO DE USO")
    void naoDeveExibirProtocoloAutorizacaoParaRejeitado() {
        DanfeData d = dadosHom();
        d.tpAmb = "2";
        d.cStat = "225";
        d.nProt = null;

        String texto = extrairTextoPagina1(generator.gerar(d));

        assertFalse(texto.contains("PROTOCOLO DE AUTORIZAÇÃO DE USO"));
        assertTrue(texto.contains("cStat: 225"));
    }

    @Test
    @DisplayName("Não lança exceção com campos opcionais nulos")
    void deveGerarPdfComCamposNulos() {
        DanfeData d = new DanfeData();
        d.tpAmb     = "2";
        d.emitXNome = "EMITENTE";
        d.destXNome = "DESTINATARIO";
        d.vProd     = "10.00";
        d.vNF       = "10.00";
        d.itens     = List.of();

        assertDoesNotThrow(() -> generator.gerar(d));
    }

    @Test
    @DisplayName("Não lança exceção com vProd em formato pt-BR (vírgula)")
    void deveGerarPdfComValorPtBr() {
        DanfeData d = dadosHom();
        d.vProd = "1.234,56";
        d.vNF   = "1.234,56";
        // formatDecimal usa Double.parseDouble com replace de vírgula — não deve lançar
        assertDoesNotThrow(() -> generator.gerar(d));
    }

    // -------------------------------------------------------------------------
    // Regressão thread-safety (DecimalFormat não é thread-safe se compartilhado)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Thread-safety: 10 gerações concorrentes sem IllegalArgumentException (regressão DecimalFormat)")
    void deveGerarConcurrentementeSemErro() throws InterruptedException {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Throwable> primeiroErro = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    DanfeData d = dadosHom();
                    // Valores distintos por thread para exercitar o formatDecimal de cada item
                    d.vProd = String.format("%.2f", 10.5 + idx * 1.3);
                    d.vNF   = d.vProd;
                    d.itens.get(0).vProd   = d.vProd;
                    d.itens.get(0).vUnCom  = String.format("%.2f", 5.25 + idx);
                    d.itens.get(0).qCom    = String.format("%.4f", 1.0 + idx * 0.5);
                    byte[] pdf = generator.gerar(d);
                    assertTrue(pdf.length > 0);
                } catch (Throwable t) {
                    primeiroErro.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        assertNull(primeiroErro.get(),
                "Geração concorrente lançou exceção: " +
                (primeiroErro.get() != null ? primeiroErro.get().getMessage() : ""));
    }
}
