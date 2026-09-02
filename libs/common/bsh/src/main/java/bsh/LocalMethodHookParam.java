package bsh;

/**
 * Parameter object passed to {@link BshHook#beforeLocalMethod(LocalMethodHookParam)}
 * before a local BeanShell method is executed.
 * <p>
 * A hook can inspect {@link #methodName} and, to short-circuit the call, set
 * {@link #isIntercepted} to {@code true} and assign {@link #returnValue}.
 */
public class LocalMethodHookParam {

    /** The name of the local BeanShell method being invoked. */
    public String methodName;

    /** Set to {@code true} by a hook to prevent the method body from running. */
    public boolean isIntercepted;

    /** The value returned to the caller when {@link #isIntercepted} is {@code true}. */
    public Object returnValue;


    /** The interpreter in which the local method is being invoked. */
    public Interpreter interpreter;

    /** The declared return type of the local method, or {@code null} if unknown. */
    public Class<?> returnType;
    public LocalMethodHookParam(String methodName) {
        this.methodName = methodName;
    }
}
