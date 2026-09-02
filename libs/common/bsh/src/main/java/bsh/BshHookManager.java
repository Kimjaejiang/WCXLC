package bsh;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages a set of {@link BshHook} instances and dispatches
 * {@link BshHook#beforeLocalMethod(LocalMethodHookParam)} notifications.
 * <p>
 * Hooks are stored in a thread-safe copy-on-write list so they can be
 * added at any time without explicit synchronisation.
 */
public class BshHookManager {

    private final List<BshHook> hooks = new CopyOnWriteArrayList<>();

    /** Registers a hook so it receives subsequent notifications. */
    public void addHook(BshHook hook) {
        if (hook != null)
            hooks.add(hook);
    }

    /** Removes a previously registered hook. */
    public void removeHook(BshHook hook) {
        hooks.remove(hook);
    }

    /**
     * Notifies every registered hook about an upcoming local method call.
     *
     * @param methodName the name of the local BeanShell method
     * @return the param object after all hooks have run; check
     *         {@link LocalMethodHookParam#isIntercepted} to decide whether
     *         to short-circuit the call.
     */
    public LocalMethodHookParam notifyBeforeLocalMethod(String methodName) {
        LocalMethodHookParam param = new LocalMethodHookParam(methodName);
        for (BshHook hook : hooks) {
            try {
                hook.beforeLocalMethod(param);
            } catch (Exception e) {
                // A faulty hook must not break method dispatch.
                Interpreter.debug("BshHook threw: ", e);
            }
            if (param.isIntercepted)
                break;
        }
        return param;
    }
}
