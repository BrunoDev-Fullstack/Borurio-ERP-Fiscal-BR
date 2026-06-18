package br.com.borurio.app.context;

public final class EmpresaContextHolder {

    private static final ThreadLocal<Long>   EMPRESA_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> JTI_AUTH   = new ThreadLocal<>();

    private EmpresaContextHolder() {}

    public static void set(Long empresaId) {
        EMPRESA_ID.set(empresaId);
    }

    public static Long get() {
        return EMPRESA_ID.get();
    }

    /** jti do token OMS da requisição corrente; null para sessões de usuário. */
    public static void setJtiAuth(String jti) {
        JTI_AUTH.set(jti);
    }

    public static String getJtiAuth() {
        return JTI_AUTH.get();
    }

    public static void clear() {
        EMPRESA_ID.remove();
        JTI_AUTH.remove();
    }
}
