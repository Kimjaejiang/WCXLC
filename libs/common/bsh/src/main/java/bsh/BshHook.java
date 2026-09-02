package bsh;

/**
 * Hook interface for intercepting local BeanShell method invocations.
 * <p>
 * Registered hooks are notified via {@link #beforeLocalMethod(LocalMethodHookParam)}
 * before a local (non-Java) BeanShell method body is executed. A hook may set
 * {@code param.isIntercepted = true} and supply a {@code param.returnValue}
 * to short-circuit the method call.
 */
public interface BshHook {
    void beforeLocalMethod(LocalMethodHookParam param);
}
