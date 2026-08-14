package br.com.borurio.fiscal.config;

import br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolve a rota SEFAZ do autorizador NORMAL a partir da UF real da empresa emitente (Fase 0 do
 * Gate SVC, 14-08-2026) — nunca de configuração global, nunca com fallback silencioso para outra
 * UF. UF sem rota configurada, ou com rota incompleta (algum dos 4 endpoints em branco), falha
 * fechado: {@link SefazRotaNaoConfiguradaException}, nenhuma tentativa de rede é feita pelo
 * chamador (ver {@code NfeTransmitServiceImpl}, que resolve a rota ANTES de abrir qualquer
 * conexão).
 *
 * {@link SefazRotaNaoConfiguradaException} é um tipo dedicado (não {@code IllegalStateException}
 * genérico) — achado de code review 14-08-2026: sem tipo próprio, o erro de configuração era
 * capturado pelo {@code catch (Exception e)} de {@code NfeOrquestradorService.processar()} e
 * reclassificado como transmissão incerta/retryable. Quem captura exceções de transmissão
 * genericamente precisa tratar este tipo à parte, antes de qualquer wrap.
 *
 * Autorizador é implicitamente NORMAL nesta fase — a assinatura ganha a dimensão de autorizador
 * lógico (NORMAL/SVC-AN/SVC-RS) só quando o Gate SVC for de fato implementado, não antes.
 */
@Component
public class SefazRotaResolver {

    private final SefazRotasProperties rotas;

    public SefazRotaResolver(SefazRotasProperties rotas) {
        this.rotas = rotas;
    }

    /**
     * Canonicalização única da UF — banca 14-08-2026 (3ª rodada): antes desta correção, cada
     * ponto do código (resolução de rota, cálculo de cUF, endereço do emitente no XML) fazia sua
     * própria normalização ad-hoc, algumas sem trim, nenhuma com {@link Locale#ROOT} — risco real
     * de divergência entre o que vai no XML/chave e o que resolve a rota de transporte. Todo
     * chamador que precisa da UF do emitente (não só o resolver) deve canonicalizar aqui, uma
     * única vez, antes de usar o valor em qualquer lugar. {@code Locale.ROOT} evita que o locale
     * padrão da JVM em produção afete o resultado de {@code toUpperCase} — não crítico para as
     * siglas de UF (ASCII puro), mas eliminar a dependência do locale default é mais seguro do
     * que confiar que ele nunca mude. Não lança para entrada nula/branca — apenas devolve o valor
     * como está, deixando a validação de obrigatoriedade para o chamador (ver {@link #resolver}).
     */
    public static String canonicalizarUf(String uf) {
        if (uf == null) return null;
        return uf.trim().toUpperCase(Locale.ROOT);
    }

    public SefazRotasProperties.Rota resolver(String uf) {
        if (uf == null || uf.isBlank()) {
            throw new IllegalArgumentException("UF é obrigatória para resolver a rota SEFAZ do autorizador normal.");
        }
        String ufNormalizada = canonicalizarUf(uf);
        SefazRotasProperties.Rota rota = rotas.getRotas().get(ufNormalizada);
        if (rota == null) {
            throw new SefazRotaNaoConfiguradaException(
                    "Nenhuma rota SEFAZ (autorizador normal) configurada para UF=" + ufNormalizada
                            + " — transmissão bloqueada antes de qualquer chamada de rede. "
                            + "Configure sefaz.rotas." + ufNormalizada + ".* ou corrija o cadastro da empresa.");
        }
        validarCompleta(ufNormalizada, rota);
        return rota;
    }

    // Achado de code review 14-08-2026: uma UF com entrada no mapa mas com algum endpoint em
    // branco (ex.: esqueceram de configurar "status:") passava pelo fail-closed antigo e só
    // quebrava depois, com NullPointerException cru dentro de NfeTransmitServiceImpl.
    private void validarCompleta(String uf, SefazRotasProperties.Rota rota) {
        List<String> faltando = new ArrayList<>();
        if (emBranco(rota.getAutorizacao())) faltando.add("autorizacao");
        if (emBranco(rota.getRetorno())) faltando.add("retorno");
        if (emBranco(rota.getConsulta())) faltando.add("consulta");
        if (emBranco(rota.getStatus())) faltando.add("status");
        if (!faltando.isEmpty()) {
            throw new SefazRotaNaoConfiguradaException(
                    "Rota SEFAZ (autorizador normal) para UF=" + uf + " está incompleta — faltando: "
                            + String.join(", ", faltando) + ". Transmissão bloqueada antes de qualquer chamada de rede. "
                            + "Complete sefaz.rotas." + uf + ".*.");
        }
    }

    private boolean emBranco(String valor) {
        return valor == null || valor.isBlank();
    }
}
