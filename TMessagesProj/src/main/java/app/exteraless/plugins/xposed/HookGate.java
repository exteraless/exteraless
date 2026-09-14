package app.exteraless.plugins.xposed;

import org.telegram.messenger.FileLog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Закрывает гонку установки хука в Aliuhook 1.1.4.
 *
 * XposedBridge.hookMethod сначала зовёт нативный hook0 (метод с этого момента уже
 * перехвачен), и только потом проставляет HookInfo.backup и добавляет колбэк. Вызов
 * метода с другого потока, попавший в это окно, видит ноль колбэков и backup == null
 * и падает NPE внутри XposedBridge.invokeMethod.
 *
 * Здесь тот же самый сценарий выполняется в безопасном порядке: запись создаётся
 * заранее, в неё кладётся барьер-колбэк, и только после этого ставится нативный хук.
 * Поток, попавший в окно, паркуется на барьере, пока backup не проставлен. Дальше
 * XposedBridge.hookMethod находит готовую запись и просто добавляет колбэк.
 */
public final class HookGate {

    private static final long BARRIER_TIMEOUT_MS = 2000L;

    private static volatile Boolean available;
    private static volatile boolean bridgeGuarded;

    private static Map<Member, Object> hookRecords;
    private static Constructor<?> hookInfoConstructor;
    private static Field backupField;
    private static Field callbacksField;
    private static Method callbacksAdd;
    private static Method callbacksRemove;
    private static Method hook0;
    private static Method callbackMethod;

    private HookGate() {
    }

    @SuppressWarnings("unchecked")
    private static synchronized boolean resolve() {
        if (available != null) {
            return available;
        }
        try {
            Class<?> bridge = Class.forName("de.robv.android.xposed.XposedBridge");
            Class<?> hookInfo = Class.forName("de.robv.android.xposed.XposedBridge$HookInfo");
            Class<?> callbackSet = Class.forName("de.robv.android.xposed.XposedBridge$CopyOnWriteSortedSet");

            Field records = bridge.getDeclaredField("hookRecords");
            records.setAccessible(true);
            hookRecords = (Map<Member, Object>) records.get(null);

            Field callback = bridge.getDeclaredField("callbackMethod");
            callback.setAccessible(true);
            callbackMethod = (Method) callback.get(null);

            hook0 = bridge.getDeclaredMethod("hook0", Object.class, Member.class, Method.class);
            hook0.setAccessible(true);

            hookInfoConstructor = hookInfo.getDeclaredConstructor(Member.class);
            hookInfoConstructor.setAccessible(true);

            backupField = hookInfo.getDeclaredField("backup");
            backupField.setAccessible(true);
            callbacksField = hookInfo.getDeclaredField("callbacks");
            callbacksField.setAccessible(true);

            callbacksAdd = callbackSet.getDeclaredMethod("add", Object.class);
            callbacksAdd.setAccessible(true);
            callbacksRemove = callbackSet.getDeclaredMethod("remove", Object.class);
            callbacksRemove.setAccessible(true);

            available = hookRecords != null && callbackMethod != null;
        } catch (Throwable t) {
            FileLog.e("HookGate: Aliuhook internals not reachable, race guard off", t);
            available = false;
        }
        return available;
    }

    public static void prewarm(Member member) {
        if (member == null || Modifier.isAbstract(member.getModifiers())) {
            return;
        }
        if (!(member instanceof Method) && !(member instanceof Constructor)) {
            return;
        }
        if (!resolve()) {
            return;
        }
        try {
            synchronized (hookRecords) {
                if (hookRecords.get(member) != null) {
                    return;
                }
                Object info = hookInfoConstructor.newInstance(member);
                Object callbacks = callbacksField.get(info);
                Barrier barrier = new Barrier();
                callbacksAdd.invoke(callbacks, barrier);
                hookRecords.put(member, info);
                Object backup = null;
                try {
                    backup = hook0.invoke(null, info, member, callbackMethod);
                } finally {
                    if (backup != null) {
                        backupField.set(info, backup);
                    } else {
                        hookRecords.remove(member);
                    }
                    barrier.open();
                    try {
                        callbacksRemove.invoke(callbacks, barrier);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e("HookGate.prewarm failed for " + member, t);
        }
    }

    public static synchronized void guardBridge() {
        if (bridgeGuarded || !resolve()) {
            return;
        }
        try {
            Method hookMethod = XposedBridge.class.getDeclaredMethod("hookMethod", Member.class, XC_MethodHook.class);
            prewarm(hookMethod);
            XposedBridge.hookMethod(hookMethod, new BridgeGuard());
            bridgeGuarded = true;
        } catch (Throwable t) {
            FileLog.e("HookGate: direct XposedBridge.hookMethod calls stay unguarded", t);
        }
    }

    public static void prewarmAllMethods(Class<?> clazz, String methodName) {
        if (clazz == null || methodName == null) {
            return;
        }
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    prewarm(method);
                }
            }
        } catch (Throwable t) {
            FileLog.e("HookGate.prewarmAllMethods failed for " + clazz + "." + methodName, t);
        }
    }

    public static void prewarmAllConstructors(Class<?> clazz) {
        if (clazz == null) {
            return;
        }
        try {
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                prewarm(constructor);
            }
        } catch (Throwable t) {
            FileLog.e("HookGate.prewarmAllConstructors failed for " + clazz, t);
        }
    }

    private static final class BridgeGuard extends XC_MethodHook {

        BridgeGuard() {
            super(PRIORITY_HIGHEST);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object member = param.args != null && param.args.length > 0 ? param.args[0] : null;
            if (member instanceof Member) {
                prewarm((Member) member);
            }
        }
    }

    private static final class Barrier extends XC_MethodHook {

        private final CountDownLatch latch = new CountDownLatch(1);

        Barrier() {
            super(PRIORITY_HIGHEST);
        }

        void open() {
            latch.countDown();
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                latch.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
