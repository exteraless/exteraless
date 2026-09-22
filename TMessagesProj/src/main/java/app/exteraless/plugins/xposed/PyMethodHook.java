package app.exteraless.plugins.xposed;

import com.chaquo.python.PyObject;
import org.telegram.ui.ActionBar.ActionBar;

import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;

/**
 * before/after-хук: вызывает Python-методы {@code before_hooked_method(param)} и
 * {@code after_hooked_method(param)} (любой из них может отсутствовать — наличие
 * проверено один раз при регистрации). MethodHookParam передаётся в Python как есть,
 * Chaquopy оборачивает его в прокси (param.thisObject, param.args, param.getResult(),
 * param.setResult(...) доступны из Python).
 *
 * Фильтры считаются на Java-стороне ДО входа в Python: "before"-фильтры гейтят
 * before_hooked_method, "after"-фильтры — after_hooked_method.
 */
public class PyMethodHook extends XC_MethodHook {

    private final String pluginId;
    private final ThreadLocal<java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView>> badgeDraws = new ThreadLocal<>();
    private final PyObject handler;
    private final boolean hasBefore;
    private final boolean hasAfter;
    private final List<HookFilter> beforeFilters;
    private final List<HookFilter> afterFilters;

    public PyMethodHook(String pluginId, PyObject handler) {
        this(pluginId, handler, PRIORITY_DEFAULT, true, true);
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority) {
        this(pluginId, handler, priority, true, true);
    }

    public PyMethodHook(String pluginId, PyObject handler, boolean before, boolean after) {
        this(pluginId, handler, PRIORITY_DEFAULT, before, after);
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority,
                 boolean before, boolean after) {
        this(pluginId, handler, priority, before, after,
                Collections.emptyList(), Collections.emptyList());
    }

    public PyMethodHook(String pluginId, PyObject handler, int priority,
                 List<HookFilter> beforeFilters, List<HookFilter> afterFilters) {
        this(pluginId, handler, priority, true, true, beforeFilters, afterFilters);
    }

    private PyMethodHook(String pluginId, PyObject handler, int priority,
                 boolean before, boolean after,
                 List<HookFilter> beforeFilters, List<HookFilter> afterFilters) {
        super(priority);
        this.pluginId = pluginId;
        this.handler = handler;
        this.hasBefore = before && handler != null
                && handler.containsKey("before_hooked_method");
        this.hasAfter = after && handler != null
                && handler.containsKey("after_hooked_method");
        this.beforeFilters = beforeFilters;
        this.afterFilters = afterFilters;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (hasAfter && param.thisObject instanceof ActionBar.UnreadImageView
                && param.method != null && "onDraw".equals(param.method.getName())
                && HookFilter.evaluateAll(afterFilters, param, false)
                && ownsUnreadBadge(param.thisObject)) {
            ActionBar.UnreadImageView view = (ActionBar.UnreadImageView) param.thisObject;
            java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView> draws = badgeDraws.get();
            if (draws == null) {
                draws = new java.util.IdentityHashMap<>();
                badgeDraws.set(draws);
            }
            draws.put(param, view);
            view.beginPluginUnreadBadge();
        }
        if (hasBefore && HookFilter.evaluateAll(beforeFilters, param, false)) {
            XposedHooks.callPython(pluginId, handler, "before_hooked_method", param);
        }
    }

    @Override
    protected void afterHookedMethod(MethodHookParam param) {
        try {
            if (hasAfter && HookFilter.evaluateAll(afterFilters, param, true)) {
                XposedHooks.callPython(pluginId, handler, "after_hooked_method", param);
            }
        } finally {
            java.util.IdentityHashMap<MethodHookParam, ActionBar.UnreadImageView> draws = badgeDraws.get();
            if (draws != null) {
                ActionBar.UnreadImageView view = draws.remove(param);
                if (view != null) {
                    view.endPluginUnreadBadge();
                }
                if (draws.isEmpty()) {
                    badgeDraws.remove();
                }
            }
        }
    }

    private PyObject badgePlugin;
    private boolean badgePluginResolved;

    private boolean ownsUnreadBadge(Object view) {
        try {
            if (!badgePluginResolved) {
                badgePlugin = handler.get("plugin");
                badgePluginResolved = true;
            }
            PyObject plugin = badgePlugin;
            if (plugin == null) {
                return false;
            }
            PyObject target = plugin.get("current_back_button");
            PyObject count = plugin.get("unread_count");
            return target != null && target.toJava(Object.class) == view
                    && count != null && count.toInt() > 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
