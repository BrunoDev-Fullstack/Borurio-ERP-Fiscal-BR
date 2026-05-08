package br.com.borurio.app.context;

public final class EmpresaContextHolder {

    private static final ThreadLocal<Long> EMPRESA_ID = new ThreadLocal<>();

    private EmpresaContextHolder() {}

    public static void set(Long empresaId) {
        EMPRESA_ID.set(empresaId);
    }

    public static Long get() {
        return EMPRESA_ID.get();
    }

    public static void clear() {
        EMPRESA_ID.remove();
    }
}
