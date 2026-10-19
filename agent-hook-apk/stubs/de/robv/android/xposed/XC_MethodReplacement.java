package de.robv.android.xposed;

/**
 * Hook that fully replaces a method's implementation (classic Xposed API).
 * Stub for compile-only validation; not a functional implementation.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    /** Replaces the method with an empty implementation returning null. */
    public static final XC_MethodReplacement DO_NOTHING = new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            return null;
        }
    };

    /** Creates a replacement that always returns the given constant value. */
    public static XC_MethodReplacement returnConstant(final Object result) {
        return new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                return result;
            }
        };
    }

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (Throwable t) {
            param.setThrowable(t);
        }
    }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
