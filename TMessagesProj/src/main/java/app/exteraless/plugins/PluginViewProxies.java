package app.exteraless.plugins;

import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import com.chaquo.python.PyProxy;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.telegram.messenger.FileLog;

import java.lang.reflect.Field;
import java.util.ArrayDeque;

public final class PluginViewProxies {

    private static final String[] LISTENER_FIELDS = {"mOnClickListener", "mOnLongClickListener", "mOnTouchListener"};

    private static boolean prepared;
    private static Field listenerInfoField;
    private static Field[] listenerFields;

    private PluginViewProxies() {
    }

    public static void release(View root) {
        if (root == null || !PluginsController.getInstance().isEngineEnabled() || !prepare()) {
            return;
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        try {
            while (!pending.isEmpty()) {
                View view = pending.poll();
                Object info = listenerInfoField.get(view);
                if (info != null) {
                    for (Field field : listenerFields) {
                        if (field.get(info) instanceof PyProxy) {
                            field.set(info, null);
                        }
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child != null) {
                            pending.add(child);
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static boolean prepare() {
        if (prepared) {
            return listenerFields != null;
        }
        prepared = true;
        try {
            Class<?> infoClass = Class.forName("android.view.View$ListenerInfo");
            Field info = find(View.class, "mListenerInfo");
            Field[] fields = new Field[LISTENER_FIELDS.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = find(infoClass, LISTENER_FIELDS[i]);
            }
            listenerInfoField = info;
            listenerFields = fields;
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static Field find(Class<?> owner, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            for (Field field : HiddenApiBypass.getInstanceFields(owner)) {
                if (name.equals(field.getName())) {
                    field.setAccessible(true);
                    return field;
                }
            }
            throw new NoSuchFieldException(name);
        }
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
