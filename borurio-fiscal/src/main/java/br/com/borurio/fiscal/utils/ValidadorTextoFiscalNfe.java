package br.com.borurio.fiscal.utils;

import java.util.Optional;

/**
 * Validação preventiva de texto destinado a campos NF-e do tipo {@code TString}
 * (tiposBasico_v4.00.xsd, pattern introduzido pela NT 2023.002 / PL_009l).
 *
 * <p>Regra oficial do {@code TString} versionado em
 * {@code borurio-fiscal/src/main/resources/xsd/oficial/tiposBasico_v4.00.xsd}:
 * <pre>{@code <xs:pattern value="[!-ÿ]{1}[ -ÿ]{0,}[!-ÿ]{1}|[!-ÿ]{1}"/>}</pre>
 * ou seja: todo caractere no intervalo {@code U+0020}–{@code U+00FF}, e o primeiro e o último
 * caractere diferentes de espaço ({@code U+0020}). Cada campo aplica ainda o seu {@code maxLength}
 * oficial.
 *
 * <p><b>Escopo V1 (02-09-2026):</b> SÓ charset + maxLength. NÃO valida {@code minLength}, NCM,
 * CFOP, CSOSN, origem, CRT, unidade nem o XML completo — cada um tem regra própria e fica para
 * uma banca posterior. <b>Fail-closed:</b> nunca sanitiza, transliterá ou substitui — só aponta
 * a violação para o chamador rejeitar.
 *
 * <p>Qualquer valor recusado aqui seria recusado pela SEFAZ por esse mesmo schema.
 */
public final class ValidadorTextoFiscalNfe {

    private ValidadorTextoFiscalNfe() {}

    /** Limite superior do intervalo {@code [ -ÿ]} / {@code [!-ÿ]} do pattern TString. */
    private static final char TSTRING_MAX_CHAR = 0x00FF;

    /** Campo NF-e alvo — carrega o {@code maxLength} oficial do respectivo {@code TString}. */
    public enum Campo {
        X_PROD(120),
        NAT_OP(60),
        X_NOME(60),
        X_LGR(60),
        NRO(60),
        X_BAIRRO(60),
        X_MUN(60),
        INF_CPL(5000);

        private final int maxLength;

        Campo(int maxLength) { this.maxLength = maxLength; }

        public int maxLength() { return maxLength; }
    }

    public enum Motivo {
        /** Caractere fora de {@code U+0020}–{@code U+00FF} (ideograma, emoji, pontuação full-width, etc.). */
        CARACTERE_NAO_PERMITIDO,
        /** Primeiro ou último caractere é espaço — proibido pelo pattern do {@code TString}. */
        ESPACO_NA_BORDA,
        /** Excede o {@code maxLength} oficial do campo. */
        ACIMA_DO_MAX_LENGTH
    }

    public record Violacao(Campo campo, Motivo motivo, int maxLength) {}

    /**
     * Valida um único valor contra a regra do campo.
     *
     * <p>{@code null} ou vazio → sem violação: presença/obrigatoriedade é responsabilidade de
     * {@code PedidoServiceImpl.validarSnapshotFiscal} / {@code @NotBlank}, e campos opcionais
     * (ex.: {@code observacao}) podem legitimamente vir nulos.
     *
     * @return a primeira violação encontrada, ou {@link Optional#empty()} se o valor é aceitável.
     */
    public static Optional<Violacao> validar(Campo campo, String valor) {
        if (valor == null || valor.isEmpty()) {
            return Optional.empty();
        }

        // 1. Charset — todo char em U+0020..U+00FF. Caracteres fora do BMP (emoji) são pares
        //    substitutos cujas duas unidades são > 0x00FF, então também caem aqui.
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            if (c < 0x20 || c > TSTRING_MAX_CHAR) {
                return Optional.of(new Violacao(campo, Motivo.CARACTERE_NAO_PERMITIDO, campo.maxLength()));
            }
        }

        // 2. Pattern TString — primeiro e último caractere não podem ser espaço (U+0020).
        if (valor.charAt(0) == ' ' || valor.charAt(valor.length() - 1) == ' ') {
            return Optional.of(new Violacao(campo, Motivo.ESPACO_NA_BORDA, campo.maxLength()));
        }

        // 3. maxLength oficial do campo.
        if (valor.length() > campo.maxLength()) {
            return Optional.of(new Violacao(campo, Motivo.ACIMA_DO_MAX_LENGTH, campo.maxLength()));
        }

        return Optional.empty();
    }
}
