package app.exteraless.plugins.utils;

import com.android.dx.Code;
import com.android.dx.Comparison;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Label;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mvel2.MVEL;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import dalvik.system.InMemoryDexClassLoader;

/**
 * Фабрика классов-прокси (dexmaker). Сигнатуры финальны — их зовёт
 * {@link app.exteraless.plugins.PluginServices} и Python-SDK (extera_utils.classes).
 *
 * Схема JSON-спеки (generateProxyClass):
 * <pre>{
 *   "name": "сегмент имени класса (custom_name или имя Python-класса)",
 *   "superclass": "fqcn (по умолчанию java.lang.Object)",
 *   "interfaces": ["fqcn", ...],
 *   "fields": [{"name", "type", "static": false, "initial": json, "getter": null, "setter": null}],
 *   "constructors": [{"sig": "(...)V", "super_sig": "(...)V"}],   // опц.; по умолчанию — все не-private ctor'ы суперкласса
 *   "methods": [{"key", "name", "sig": "(...)R"|null, "return": "void|fqcn|primitive"|null,
 *                "super": true|false, "mvel": "expr"|null}]
 * }</pre>
 *
 * Диспетчеризация: каждый сгенерированный метод — статический вызов
 * {@link #dispatch(String, String, Object, Object, Object[])} с зашитыми в байткод
 * classKey/methodKey; peer (PyObject) лежит в volatile-поле {@code __extera_peer__}
 * самого инстанса (реестр не нужен; освобождение — {@link #releaseProxyInstance(Object)}
 * или Python-side Base.release()).
 *
 * super(): в классе генерируется публичный trampoline {@code super$<name>(args)} с
 * прямым invoke-super; {@link #invokeSuper} находит его рефлексией по methodSig
 * (= spec key; перегрузки различаются по арности args).
 *
 * ClassLoader: цепочка InMemoryDexClassLoader (minSdk=27), каждый следующий класс
 * грузится лоадером с parent = предыдущий лоадер — видны все ранее сгенерированные
 * классы (прокси может наследовать прокси), коллизий имён нет за счёт random-суффикса.
 * Повторный (pluginId + sha256(spec)) → кэш, класс не перегенерируется.
 */
public final class ClassProxyFactory {

    private static final String TAG = "ClassProxyFactory";
    private static final String GENERATED_PACKAGE = "app.exteraless.plugins.proxy.";
    private static final String PEER_FIELD_NAME = "__extera_peer__";
    private static final String SUPER_PREFIX = "super$";
    private static final String DISPATCH_ATTR = "__extera_dispatch__";
    private static final String PRECONSTRUCT_ATTR = "__extera_java_preconstruct__";
    private static final String CLASSES_MODULE = "extera_utils.classes";
    private static final Object[] EMPTY_ARGS = new Object[0];

    /** classKey -> сгенерированный класс и его методы. */
    private static final ConcurrentHashMap<String, ClassRecord> CLASSES = new ConcurrentHashMap<>();
    /** pluginId -> classKey'и (для releaseAllForPlugin). */
    private static final ConcurrentHashMap<String, Set<String>> PLUGIN_CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Serializable> MVEL_CACHE = new ConcurrentHashMap<>();
    private static final Object GENERATE_LOCK = new Object();
    private static final Random RANDOM = new Random();

    private static volatile ClassLoader sharedGeneratedClassLoader;

    private static final TypeId<ClassProxyFactory> SELF_ID = TypeId.get(ClassProxyFactory.class);
    private static final TypeId<TypeHelper> HELPER_ID = TypeId.get(TypeHelper.class);
    private static final TypeId<Object> OBJECT_ID = TypeId.OBJECT;
    private static final TypeId<Object[]> OBJECT_ARRAY_ID = TypeId.get(Object[].class);
    @SuppressWarnings("rawtypes")
    private static final MethodId DISPATCH_ID = SELF_ID.getMethod(OBJECT_ID, "dispatch",
            TypeId.STRING, TypeId.STRING, OBJECT_ID, OBJECT_ID, OBJECT_ARRAY_ID);
    @SuppressWarnings("rawtypes")
    private static final MethodId PRECONSTRUCT_ID = SELF_ID.getMethod(OBJECT_ARRAY_ID, "preConstruct",
            TypeId.STRING, TypeId.STRING, OBJECT_ARRAY_ID);

    /** Текст последнего отказа генератора: питон видит только «see logcat». */
    private static volatile String lastError;

    public static String getLastError() {
        return lastError;
    }

    /** Отказ до генератора (нет прав, нет контекста) тоже должен доезжать до плагина. */
    public static void setLastError(String error) {
        lastError = error;
    }

    private ClassProxyFactory() {
    }

    // ---------- публичный фасад (сигнатуры финальны) ----------

    public static String generateProxyClass(String pluginId, String specJson) {
        try {
            return generateInternal(pluginId, specJson);
        } catch (Throwable t) {
            // Через logcat, а не только FileLog: причина нужна и тогда, когда логи выключены,
            // иначе плагин видит лишь «see logcat», а в logcat пусто.
            android.util.Log.e(TAG, "generateProxyClass failed for plugin " + pluginId, t);
            FileLog.e(TAG + ": generateProxyClass failed for plugin " + pluginId, t);
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    public static boolean needsHooks(String specJson) {
        try {
            JSONObject spec = new JSONObject(specJson);
            ClassLoader loader = sharedGeneratedClassLoader != null
                    ? sharedGeneratedClassLoader : appClassLoader();
            return needsHooks(resolveTypeName(optString(spec, "superclass", "java.lang.Object"), loader));
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean classNeedsHooks(String classKey) {
        ClassRecord rec = CLASSES.get(classKey);
        return rec == null || needsHooks(rec.clazz.getSuperclass());
    }

    private static boolean needsHooks(Class<?> superclass) {
        if (superclass == null || superclass == Object.class) {
            return false;
        }
        String name = superclass.getName();
        if (name.startsWith("android.") || name.startsWith("androidx.")) {
            return false;
        }
        if (android.view.View.class.isAssignableFrom(superclass)) {
            return false;
        }
        return !org.telegram.ui.Components.UItem.UItemFactory.class.isAssignableFrom(superclass);
    }

    public static Object newProxyInstance(String pluginId, String classKey, String ctorSig,
                                          Object[] args, PyObject peer) {
        ClassRecord rec = CLASSES.get(classKey);
        if (rec == null) {
            FileLog.e(TAG + ": newProxyInstance: unknown classKey " + classKey);
            return null;
        }
        if (args == null) {
            args = EMPTY_ARGS;
        }
        try {
            Constructor<?> ctor = selectConstructor(rec, ctorSig, args);
            if (ctor == null) {
                FileLog.e(TAG + ": newProxyInstance: no matching constructor for "
                        + rec.clazz.getName() + " (sig=" + ctorSig + ", argc=" + args.length + ")");
                return null;
            }
            Object instance = ctor.newInstance(coerceArgs(args, ctor.getParameterTypes()));
            Field peerField = rec.clazz.getField(PEER_FIELD_NAME);
            peerField.set(instance, peer);
            for (FieldInitial initial : rec.instanceInitials) {
                initial.field.set(instance, coerce(initial.value, initial.field.getType()));
            }
            return instance;
        } catch (Throwable t) {
            FileLog.e(TAG + ": newProxyInstance failed for " + classKey, t);
            return null;
        }
    }

    public static Object getProxyClass(String classKey) {
        ClassRecord rec = CLASSES.get(classKey);
        return rec != null ? rec.clazz : null;
    }

    public static Object invokeSuper(String classKey, Object proxy, String methodSig, Object[] args) {
        ClassRecord rec = CLASSES.get(classKey);
        if (rec == null || proxy == null) {
            return null;
        }
        if (args == null) {
            args = EMPTY_ARGS;
        }
        List<MethodRecord> candidates = rec.byPyKey.get(methodSig);
        if (candidates == null) {
            MethodRecord direct = rec.methods.get(methodSig);
            if (direct != null) {
                candidates = Collections.singletonList(direct);
            }
        }
        if (candidates == null || candidates.isEmpty()) {
            FileLog.e(TAG + ": invokeSuper: unknown method " + methodSig + " for " + classKey);
            return null;
        }
        MethodRecord mr = null;
        for (MethodRecord candidate : candidates) {
            if (candidate.paramTypes.length == args.length) {
                mr = candidate;
                break;
            }
        }
        if (mr == null) {
            mr = candidates.get(0);
        }
        if (mr.trampoline == null) {
            FileLog.e(TAG + ": invokeSuper: no super implementation for " + methodSig);
            return defaultValue(mr.returnType);
        }
        try {
            return mr.trampoline.invoke(proxy, coerceArgs(args, mr.paramTypes));
        } catch (Throwable t) {
            FileLog.e(TAG + ": invokeSuper failed for " + methodSig, t);
            return defaultValue(mr.returnType);
        }
    }

    /** Отвязать Python-peer от инстанса (разрывает cross-heap цикл proxy<->peer). */
    public static void releaseProxyInstance(Object proxy) {
        if (proxy == null) {
            return;
        }
        try {
            Field field = proxy.getClass().getField(PEER_FIELD_NAME);
            field.set(proxy, null);
        } catch (Throwable ignore) {
        }
    }

    /**
     * Выгрузка классов плагина из реестров. Сами dex-классы остаются загруженными
     * (ART не выгружает классы); диспетчеризация по удалённым ключам становится no-op.
     * Интегратору: звать из PluginsController при unload плагина.
     */
    public static void releaseAllForPlugin(String pluginId) {
        Set<String> keys = PLUGIN_CLASSES.remove(pluginId);
        if (keys == null) {
            return;
        }
        for (String key : keys) {
            CLASSES.remove(key);
        }
    }

    // ---------- точки входа из сгенерированного байткода (public!) ----------

    /**
     * Единая диспетчеризация методов прокси. peer == null (конструктор суперкласса,
     * прямое Java-создание) → fallback на super-trampoline или default-значение.
     */
    public static Object dispatch(String classKey, String methodKey, Object peer, Object proxy, Object[] args) {
        ClassRecord rec = CLASSES.get(classKey);
        if (rec == null) {
            return null;
        }
        MethodRecord mr = rec.methods.get(methodKey);
        if (mr == null) {
            return null;
        }
        if (args == null) {
            args = EMPTY_ARGS;
        }
        if (mr.mvel != null) {
            return executeMvel(mr, peer, proxy, args);
        }
        if (peer instanceof PyObject) {
            try {
                Object[] callArgs = new Object[2 + args.length];
                callArgs[0] = mr.pyKey;
                callArgs[1] = proxy;
                System.arraycopy(args, 0, callArgs, 2, args.length);
                return convertResult(((PyObject) peer).callAttr(DISPATCH_ATTR, callArgs), mr.returnType);
            } catch (Throwable t) {
                FileLog.e(TAG + ": python dispatch failed for " + methodKey, t);
            }
        }
        if (mr.trampoline != null && proxy != null) {
            try {
                return mr.trampoline.invoke(proxy, args);
            } catch (Throwable t) {
                FileLog.e(TAG + ": super fallback failed for " + methodKey, t);
            }
        }
        return defaultValue(mr.returnType);
    }

    /**
     * Pre-construct hook: вызывается из сгенерированного конструктора ДО super.&lt;init&gt;.
     * Идёт в Python-модуль extera_utils.classes (classKey зарегистрирован там Python-стороной
     * до первой инстанциации). Возврат: новые аргументы или null (= оставить как есть).
     */
    public static Object[] preConstruct(String classKey, String ctorSig, Object[] args) {
        try {
            if (!Python.isStarted()) {
                return null;
            }
            PyObject module = Python.getInstance().getModule(CLASSES_MODULE);
            if (module == null) {
                return null;
            }
            Object[] callArgs = new Object[2 + args.length];
            callArgs[0] = classKey;
            callArgs[1] = ctorSig;
            System.arraycopy(args, 0, callArgs, 2, args.length);
            PyObject result = module.callAttr(PRECONSTRUCT_ATTR, callArgs);
            if (result == null) {
                return null;
            }
            try {
                Object[] arr = result.toJava(Object[].class);
                if (arr != null) {
                    return arr;
                }
            } catch (Throwable ignore) {
            }
            try {
                List<PyObject> items = result.asList();
                if (items != null) {
                    Object[] out = new Object[items.size()];
                    for (int i = 0; i < items.size(); i++) {
                        out[i] = items.get(i) != null ? items.get(i).toJava(Object.class) : null;
                    }
                    return out;
                }
            } catch (Throwable ignore) {
            }
            return null;
        } catch (Throwable t) {
            FileLog.e(TAG + ": preConstruct failed for " + classKey, t);
            return null;
        }
    }

    /** Боксинг/анбоксинг для сгенерированного байткода (public — зовётся из dex). */
    public static final class TypeHelper {
        public static Object box(byte v) { return v; }
        public static Object box(char v) { return v; }
        public static Object box(double v) { return v; }
        public static Object box(float v) { return v; }
        public static Object box(int v) { return v; }
        public static Object box(long v) { return v; }
        public static Object box(Object v) { return v; }
        public static Object box(short v) { return v; }
        public static Object box(boolean v) { return v; }

        public static boolean unboxBool(Object v) { return v instanceof Boolean && (Boolean) v; }
        public static byte unboxByte(Object v) { return v instanceof Number ? ((Number) v).byteValue() : 0; }
        public static char unboxChar(Object v) {
            if (v instanceof Character) return (Character) v;
            if (v instanceof Number) return (char) ((Number) v).intValue();
            return 0;
        }
        public static double unboxDouble(Object v) { return v instanceof Number ? ((Number) v).doubleValue() : 0.0d; }
        public static float unboxFloat(Object v) { return v instanceof Number ? ((Number) v).floatValue() : 0.0f; }
        public static int unboxInt(Object v) { return v instanceof Number ? ((Number) v).intValue() : 0; }
        public static long unboxLong(Object v) { return v instanceof Number ? ((Number) v).longValue() : 0L; }
        public static short unboxShort(Object v) { return v instanceof Number ? ((Number) v).shortValue() : 0; }
    }

    // ---------- записи реестра ----------

    private static final class MethodRecord {
        String pyKey;          // spec "key" — его знает Python-сторона
        Class<?>[] paramTypes;
        Class<?> returnType;
        String mvel;           // null → диспетчеризация в Python
        volatile Method trampoline;  // super$<name> или null (abstract/interface/new)
    }

    private static final class FieldInitial {
        final Field field;
        final Object value;

        FieldInitial(Field field, Object value) {
            this.field = field;
            this.value = value;
        }
    }

    private static final class ClassRecord {
        final Class<?> clazz;
        final Map<String, MethodRecord> methods = new ConcurrentHashMap<>();       // expandedKey -> record
        final Map<String, List<MethodRecord>> byPyKey = new ConcurrentHashMap<>(); // pyKey -> перегрузки
        final List<FieldInitial> instanceInitials = new ArrayList<>();

        ClassRecord(Class<?> clazz) {
            this.clazz = clazz;
        }
    }

    private static final class FieldSpecLite {
        String name;
        Class<?> type;
        boolean isStatic;
        boolean hasInitial;
        Object initial;
        String getter;
        String setter;
    }

    private static final class MethodSpecLite {
        String key;
        String name;
        String sig;
        String returnName;
        boolean wantSuper;
        String mvel;
    }

    private static final class PendingMethod {
        String name;
        Class<?>[] params;
        Class<?> returnType;
        String pyKey;
        String mvel;
        Method superMethod;  // null → новый метод (jmethod)
        boolean trampoline;
    }

    // ---------- генерация ----------

    private static String generateInternal(String pluginId, String specJson) throws Exception {
        String classKey = (pluginId != null ? pluginId : "?") + ":" + sha256Hex(specJson);
        if (CLASSES.containsKey(classKey)) {
            return classKey;
        }

        JSONObject spec = new JSONObject(specJson);
        ClassLoader resolveLoader = sharedGeneratedClassLoader != null
                ? sharedGeneratedClassLoader : appClassLoader();

        String superName = optString(spec, "superclass", "java.lang.Object");
        Class<?> superclass = resolveTypeName(superName, resolveLoader);
        if (superclass.isPrimitive() || superclass.isArray() || superclass.isInterface()) {
            throw new IllegalArgumentException("superclass must be a class, got " + superName);
        }
        if (Modifier.isFinal(superclass.getModifiers())) {
            throw new IllegalArgumentException("cannot subclass final class " + superName);
        }

        List<Class<?>> interfaces = new ArrayList<>();
        JSONArray ifaces = spec.optJSONArray("interfaces");
        if (ifaces != null) {
            for (int i = 0; i < ifaces.length(); i++) {
                String name = app.exteraless.plugins.JsonUtils.optStringOrNull(ifaces, i);
                if (name == null || name.isEmpty()) {
                    continue;
                }
                Class<?> iface = resolveTypeName(name, resolveLoader);
                if (!iface.isInterface()) {
                    throw new IllegalArgumentException("expected interface, got " + name);
                }
                interfaces.add(iface);
            }
        }

        List<FieldSpecLite> fieldSpecs = parseFields(spec, resolveLoader);
        List<MethodSpecLite> methodSpecs = parseMethodSpecs(spec);

        synchronized (GENERATE_LOCK) {
            if (CLASSES.containsKey(classKey)) {
                return classKey;
            }
            ClassRecord rec = doGenerate(pluginId, classKey, spec, superclass, interfaces,
                    fieldSpecs, methodSpecs, resolveLoader);
            CLASSES.put(classKey, rec);
            if (pluginId != null) {
                PLUGIN_CLASSES.computeIfAbsent(pluginId, k -> ConcurrentHashMap.newKeySet()).add(classKey);
            }
            return classKey;
        }
    }

    private static ClassRecord doGenerate(String pluginId, String classKey, JSONObject spec,
                                          Class<?> superclass, List<Class<?>> interfaces,
                                          List<FieldSpecLite> fieldSpecs, List<MethodSpecLite> methodSpecs,
                                          ClassLoader resolveLoader) throws Exception {
        // --- разрешение методов спеки в конкретные сигнатуры ---
        Map<String, Method> overridable = getAllOverridableMethods(superclass, interfaces);
        LinkedHashMap<String, PendingMethod> pending = new LinkedHashMap<>();
        for (MethodSpecLite ms : methodSpecs) {
            if (ms.name == null || ms.name.isEmpty()) {
                continue;
            }
            if (ms.name.startsWith(SUPER_PREFIX)) {
                throw new IllegalArgumentException("method name reserved: " + ms.name);
            }
            if (ms.sig != null && !ms.sig.isEmpty()) {
                Class<?>[] params = parseParameterTypes(ms.sig, resolveLoader);
                Method superMethod = overridable.get(resolutionKey(ms.name, params));
                if (superMethod != null) {
                    addOverride(pending, ms, superMethod);
                } else {
                    Class<?> ret = ms.returnName != null
                            ? resolveTypeName(ms.returnName, resolveLoader)
                            : parseReturnType(ms.sig, resolveLoader);
                    if (ms.wantSuper) {
                        FileLog.e(TAG + ": '" + ms.name + "' marked as override but no super method "
                                + ms.sig + " found; generating as a new method");
                    }
                    addNew(pending, ms, params, ret);
                }
            } else {
                boolean found = false;
                for (Method m : overridable.values()) {
                    if (m.getName().equals(ms.name)) {
                        addOverride(pending, ms, m);
                        found = true;
                    }
                }
                if (!found) {
                    throw new NoSuchMethodException("no overridable method '" + ms.name
                            + "' in " + superclass.getName() + " or its interfaces");
                }
            }
        }
        // Непокрытые abstract-методы — иначе класс не загрузится. Тело — обычная
        // диспетчеризация; Python-обработчика нет → default-значение.
        for (Method m : overridable.values()) {
            if (Modifier.isAbstract(m.getModifiers()) && !pending.containsKey(expandedKey(m))) {
                PendingMethod pm = new PendingMethod();
                pm.name = m.getName();
                pm.params = m.getParameterTypes();
                pm.returnType = m.getReturnType();
                pm.pyKey = expandedKey(m);
                pm.superMethod = m;
                pm.trampoline = false;
                pending.put(expandedKey(m), pm);
            }
        }

        // --- имя класса ---
        String simple = superclass.getSimpleName();
        if (simple.isEmpty()) {
            simple = "Anonymous";
        }
        String segment = sanitize(optString(spec, "name", null));
        String className = GENERATED_PACKAGE + "Proxy_" + simple
                + (segment != null ? "_" + segment : "")
                + "_" + Math.abs(superclass.getName().hashCode())
                + "_" + Math.abs(RANDOM.nextInt());

        // --- dex ---
        DexMaker dexMaker = new DexMaker();
        TypeId<?> proxyType = TypeId.get("L" + className.replace('.', '/') + ";");
        TypeId<?> superType = TypeId.get(superclass);
        TypeId<?>[] ifaceIds = new TypeId[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++) {
            ifaceIds[i] = TypeId.get(interfaces.get(i));
        }
        dexMaker.declare(proxyType, "ClassProxyFactory.java", Modifier.PUBLIC, superType, ifaceIds);

        @SuppressWarnings("rawtypes")
        FieldId peerField = proxyType.getField(OBJECT_ID, PEER_FIELD_NAME);
        dexMaker.declare(peerField, Modifier.PUBLIC | Modifier.VOLATILE, null);

        for (FieldSpecLite f : fieldSpecs) {
            if (PEER_FIELD_NAME.equals(f.name)) {
                throw new IllegalArgumentException("field name reserved: " + f.name);
            }
            TypeId<?> fieldType = TypeId.get(f.type);
            @SuppressWarnings("rawtypes")
            FieldId fid = proxyType.getField(fieldType, f.name);
            dexMaker.declare(fid, Modifier.PUBLIC | (f.isStatic ? Modifier.STATIC : 0), null);
            if (f.getter != null) {
                generateFieldAccessor(dexMaker, proxyType, f.name, f.type, f.isStatic, f.getter, true);
            }
            if (f.setter != null) {
                generateFieldAccessor(dexMaker, proxyType, f.name, f.type, f.isStatic, f.setter, false);
            }
        }

        List<Class<?>[]> ctorParams = constructorParams(spec, superclass, resolveLoader);
        for (Class<?>[] params : ctorParams) {
            generateConstructor(dexMaker, proxyType, superType, params, classKey,
                    "init|" + paramsDescriptor(params) + "V");
        }

        for (Map.Entry<String, PendingMethod> entry : pending.entrySet()) {
            PendingMethod pm = entry.getValue();
            generateDispatchMethod(dexMaker, proxyType, pm.name, pm.returnType, pm.params,
                    peerField, classKey, entry.getKey());
            if (pm.trampoline) {
                generateSuperTrampoline(dexMaker, proxyType, pm.superMethod);
            }
        }

        byte[] dex = dexMaker.generate();

        // --- загрузка: цепочка InMemoryDexClassLoader ---
        Class<?> cls;
        synchronized (GENERATE_LOCK) {
            ClassLoader parent = sharedGeneratedClassLoader != null
                    ? sharedGeneratedClassLoader : appClassLoader();
            InMemoryDexClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), parent);
            cls = loader.loadClass(className);
            sharedGeneratedClassLoader = loader;
        }

        ClassRecord rec = new ClassRecord(cls);
        for (Map.Entry<String, PendingMethod> entry : pending.entrySet()) {
            PendingMethod pm = entry.getValue();
            MethodRecord mr = new MethodRecord();
            mr.pyKey = pm.pyKey != null ? pm.pyKey : entry.getKey();
            mr.paramTypes = pm.params;
            mr.returnType = pm.returnType;
            mr.mvel = pm.mvel;
            if (pm.trampoline) {
                try {
                    mr.trampoline = cls.getMethod(SUPER_PREFIX + pm.name, pm.params);
                } catch (NoSuchMethodException e) {
                    FileLog.e(TAG + ": trampoline missing for " + pm.name, e);
                }
            }
            rec.methods.put(entry.getKey(), mr);
            List<MethodRecord> list = rec.byPyKey.get(mr.pyKey);
            if (list == null) {
                list = new ArrayList<>();
                rec.byPyKey.put(mr.pyKey, list);
            }
            list.add(mr);
        }

        for (FieldSpecLite f : fieldSpecs) {
            if (!f.hasInitial) {
                continue;
            }
            Field field = cls.getField(f.name);
            if (f.isStatic) {
                field.set(null, coerce(f.initial, f.type));
            } else {
                rec.instanceInitials.add(new FieldInitial(field, f.initial));
            }
        }
        return rec;
    }

    private static void addOverride(LinkedHashMap<String, PendingMethod> pending,
                                    MethodSpecLite ms, Method superMethod) {
        PendingMethod pm = new PendingMethod();
        pm.name = superMethod.getName();
        pm.params = superMethod.getParameterTypes();
        pm.returnType = superMethod.getReturnType();
        pm.pyKey = ms.key;
        pm.mvel = ms.mvel;
        pm.superMethod = superMethod;
        pm.trampoline = !Modifier.isAbstract(superMethod.getModifiers())
                && !superMethod.getDeclaringClass().isInterface();
        String key = expandedKey(superMethod);
        if (pending.containsKey(key)) {
            FileLog.e(TAG + ": duplicate method spec for " + key + ", keeping the first");
            return;
        }
        pending.put(key, pm);
    }

    private static void addNew(LinkedHashMap<String, PendingMethod> pending,
                               MethodSpecLite ms, Class<?>[] params, Class<?> ret) {
        PendingMethod pm = new PendingMethod();
        pm.name = ms.name;
        pm.params = params;
        pm.returnType = ret;
        pm.pyKey = ms.key;
        pm.mvel = ms.mvel;
        pm.superMethod = null;
        pm.trampoline = false;
        String key = expandedKey(ms.name, params, ret);
        if (pending.containsKey(key)) {
            FileLog.e(TAG + ": duplicate method spec for " + key + ", keeping the first");
            return;
        }
        pending.put(key, pm);
    }

    // ---------- кодогенерация dexmaker ----------

    // ВНИМАНИЕ: com.android.dx API строго generic-типизирован (move/compare требуют
    // одинаковый T с обеих сторон, invokeDirect — receiver Local<? extends D> от D
    // MethodId). Capture'ы от TypeId<?> между собой не унифицируются, поэтому вся
    // кодогенерация ниже работает на raw Local/MethodId/FieldId (как и референс).
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateDispatchMethod(DexMaker dexMaker, TypeId<?> proxyType, String name,
                                               Class<?> returnType, Class<?>[] paramTypes,
                                               FieldId peerField, String classKey, String methodKey) {
        TypeId<?>[] paramTypeIds = toTypeIds(paramTypes);
        TypeId<?> retTypeId = TypeId.get(returnType);
        Code code = dexMaker.declare(proxyType.getMethod(retTypeId, name, paramTypeIds), Modifier.PUBLIC);
        Local thisLocal = code.getThis(proxyType);
        Local peerLocal = code.newLocal(OBJECT_ID);
        Local classKeyLocal = code.newLocal(TypeId.STRING);
        Local methodKeyLocal = code.newLocal(TypeId.STRING);
        Local argsLocal = code.newLocal(OBJECT_ARRAY_ID);
        Local sizeLocal = code.newLocal(TypeId.INT);
        Local indexLocal = code.newLocal(TypeId.INT);
        Local resultLocal = code.newLocal(OBJECT_ID);
        // dexmaker разрешает заводить локальные только до первой инструкции,
        // поэтому и боксы аргументов, и переменная возврата объявляются здесь.
        Local[] boxedLocals = new Local[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            boxedLocals[i] = code.newLocal(OBJECT_ID);
        }
        Local returnLocal = returnType == void.class || returnType == Void.class
                ? null : code.newLocal(retTypeId);

        code.iget(peerField, peerLocal, thisLocal);
        code.loadConstant(classKeyLocal, classKey);
        code.loadConstant(methodKeyLocal, methodKey);
        code.loadConstant(sizeLocal, paramTypes.length);
        code.newArray(argsLocal, sizeLocal);
        for (int i = 0; i < paramTypes.length; i++) {
            Local param = code.getParameter(i, paramTypeIds[i]);
            boxValue(code, paramTypes[i], paramTypeIds[i], param, boxedLocals[i]);
            code.loadConstant(indexLocal, i);
            code.aput(argsLocal, indexLocal, boxedLocals[i]);
        }
        code.invokeStatic(DISPATCH_ID, resultLocal, classKeyLocal, methodKeyLocal, peerLocal,
                thisLocal, argsLocal);
        emitReturn(code, returnType, resultLocal, returnLocal);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateSuperTrampoline(DexMaker dexMaker, TypeId<?> proxyType, Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        TypeId<?>[] paramTypeIds = toTypeIds(paramTypes);
        TypeId<?> retTypeId = TypeId.get(method.getReturnType());
        Code code = dexMaker.declare(
                proxyType.getMethod(retTypeId, SUPER_PREFIX + method.getName(), paramTypeIds),
                Modifier.PUBLIC);
        Local thisLocal = code.getThis(proxyType);
        Local[] params = new Local[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            params[i] = code.getParameter(i, paramTypeIds[i]);
        }
        Local retLocal = method.getReturnType() == void.class ? null : code.newLocal(retTypeId);
        MethodId superMethod = TypeId.get(method.getDeclaringClass())
                .getMethod(retTypeId, method.getName(), paramTypeIds);
        code.invokeSuper(superMethod, retLocal, thisLocal, params);
        if (retLocal == null) {
            code.returnVoid();
        } else {
            code.returnValue(retLocal);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateConstructor(DexMaker dexMaker, TypeId<?> proxyType, TypeId<?> superType,
                                            Class<?>[] paramTypes, String classKey, String ctorKey) {
        TypeId<?>[] paramTypeIds = toTypeIds(paramTypes);
        Code code = dexMaker.declare(proxyType.getConstructor(paramTypeIds), Modifier.PUBLIC);
        Local thisLocal = code.getThis(proxyType);
        Local classKeyLocal = code.newLocal(TypeId.STRING);
        Local ctorKeyLocal = code.newLocal(TypeId.STRING);
        Local argsLocal = code.newLocal(OBJECT_ARRAY_ID);
        Local replacedLocal = code.newLocal(OBJECT_ARRAY_ID);
        Local nullLocal = code.newLocal(OBJECT_ID);
        Local sizeLocal = code.newLocal(TypeId.INT);
        Local indexLocal = code.newLocal(TypeId.INT);
        Local elemLocal = code.newLocal(OBJECT_ID);
        Local[] typedParams = new Local[paramTypes.length];
        Local[] boxedParams = new Local[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            typedParams[i] = code.newLocal(paramTypeIds[i]);
            boxedParams[i] = code.newLocal(OBJECT_ID);
        }

        for (int i = 0; i < paramTypes.length; i++) {
            code.move(typedParams[i], code.getParameter(i, paramTypeIds[i]));
        }
        code.loadConstant(classKeyLocal, classKey);
        code.loadConstant(ctorKeyLocal, ctorKey);
        code.loadConstant(sizeLocal, paramTypes.length);
        code.newArray(argsLocal, sizeLocal);
        for (int i = 0; i < paramTypes.length; i++) {
            boxValue(code, paramTypes[i], paramTypeIds[i], typedParams[i], boxedParams[i]);
            code.loadConstant(indexLocal, i);
            code.aput(argsLocal, indexLocal, boxedParams[i]);
        }
        code.loadConstant(nullLocal, null);
        code.invokeStatic(PRECONSTRUCT_ID, replacedLocal, classKeyLocal, ctorKeyLocal, argsLocal);
        Label keepOriginal = new Label();
        code.compare(Comparison.EQ, keepOriginal, replacedLocal, nullLocal);
        for (int i = 0; i < paramTypes.length; i++) {
            code.loadConstant(indexLocal, i);
            code.aget(elemLocal, replacedLocal, indexLocal);
            if (paramTypes[i].isPrimitive()) {
                MethodId unbox = HELPER_ID.getMethod(paramTypeIds[i],
                        "unbox" + capitalize(paramTypes[i].getName()), OBJECT_ID);
                code.invokeStatic(unbox, typedParams[i], elemLocal);
            } else {
                code.cast(typedParams[i], elemLocal);
            }
        }
        code.mark(keepOriginal);
        MethodId superCtor = superType.getConstructor(paramTypeIds);
        code.invokeDirect(superCtor, null, thisLocal, typedParams);
        code.returnVoid();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void generateFieldAccessor(DexMaker dexMaker, TypeId<?> proxyType, String fieldName,
                                              Class<?> fieldType, boolean isStatic, String accessorName,
                                              boolean getter) {
        TypeId<?> fieldTypeId = TypeId.get(fieldType);
        FieldId fieldId = proxyType.getField(fieldTypeId, fieldName);
        if (getter) {
            Code code = dexMaker.declare(proxyType.getMethod(fieldTypeId, accessorName), Modifier.PUBLIC);
            Local value = code.newLocal(fieldTypeId);
            if (isStatic) {
                code.sget(fieldId, value);
            } else {
                code.iget(fieldId, value, code.getThis(proxyType));
            }
            code.returnValue(value);
        } else {
            Code code = dexMaker.declare(proxyType.getMethod(TypeId.VOID, accessorName, fieldTypeId),
                    Modifier.PUBLIC);
            Local param = code.getParameter(0, fieldTypeId);
            if (isStatic) {
                code.sput(fieldId, param);
            } else {
                code.iput(fieldId, code.getThis(proxyType), param);
            }
            code.returnVoid();
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void boxValue(Code code, Class<?> type, TypeId<?> typeId, Local value, Local out) {
        MethodId box = HELPER_ID.getMethod(OBJECT_ID, "box", type.isPrimitive() ? typeId : OBJECT_ID);
        code.invokeStatic(box, out, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void emitReturn(Code code, Class<?> returnType,
                                   Local resultLocal, Local typed) {
        if (typed == null) {
            code.returnVoid();
            return;
        }
        TypeId<?> retTypeId = TypeId.get(returnType);
        if (returnType.isPrimitive()) {
            MethodId unbox = HELPER_ID.getMethod(retTypeId,
                    "unbox" + capitalize(returnType.getName()), OBJECT_ID);
            code.invokeStatic(unbox, typed, resultLocal);
        } else {
            code.cast(typed, resultLocal);
        }
        code.returnValue(typed);
    }

    // ---------- разбор спеки ----------

    private static List<FieldSpecLite> parseFields(JSONObject spec, ClassLoader cl) throws ClassNotFoundException {
        List<FieldSpecLite> out = new ArrayList<>();
        JSONArray fields = spec.optJSONArray("fields");
        if (fields == null) {
            return out;
        }
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = fields.optJSONObject(i);
            if (f == null) {
                continue;
            }
            FieldSpecLite lite = new FieldSpecLite();
            lite.name = optString(f, "name", null);
            String typeName = optString(f, "type", null);
            if (lite.name == null || typeName == null) {
                continue;
            }
            lite.type = resolveTypeName(typeName, cl);
            if (lite.type == void.class) {
                throw new IllegalArgumentException("field " + lite.name + " cannot be void");
            }
            lite.isStatic = f.optBoolean("static", false);
            lite.hasInitial = f.has("initial") && !f.isNull("initial");
            if (lite.hasInitial) {
                lite.initial = f.opt("initial");
            }
            lite.getter = optString(f, "getter", null);
            lite.setter = optString(f, "setter", null);
            out.add(lite);
        }
        return out;
    }

    private static List<MethodSpecLite> parseMethodSpecs(JSONObject spec) {
        List<MethodSpecLite> out = new ArrayList<>();
        JSONArray methods = spec.optJSONArray("methods");
        if (methods == null) {
            return out;
        }
        for (int i = 0; i < methods.length(); i++) {
            JSONObject m = methods.optJSONObject(i);
            if (m == null) {
                continue;
            }
            MethodSpecLite lite = new MethodSpecLite();
            lite.name = optString(m, "name", null);
            lite.key = optString(m, "key", null);
            lite.sig = optString(m, "sig", null);
            lite.returnName = optString(m, "return", null);
            lite.wantSuper = m.optBoolean("super", true);
            lite.mvel = optString(m, "mvel", null);
            out.add(lite);
        }
        return out;
    }

    private static List<Class<?>[]> constructorParams(JSONObject spec, Class<?> superclass,
                                                      ClassLoader cl) throws ClassNotFoundException {
        List<Class<?>[]> out = new ArrayList<>();
        JSONArray ctors = spec.optJSONArray("constructors");
        if (ctors != null && ctors.length() > 0) {
            for (int i = 0; i < ctors.length(); i++) {
                JSONObject c = ctors.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                String sig = optString(c, "sig", null);
                if (sig == null) {
                    continue;
                }
                String superSig = optString(c, "super_sig", null);
                if (superSig != null && !superSig.equals(sig)) {
                    FileLog.e(TAG + ": constructor super_sig != sig is not supported, using sig");
                }
                out.add(parseParameterTypes(sig, cl));
            }
            return out;
        }
        for (Constructor<?> ctor : superclass.getDeclaredConstructors()) {
            if (!Modifier.isPrivate(ctor.getModifiers())) {
                out.add(ctor.getParameterTypes());
            }
        }
        return out;
    }

    // ---------- рефлексия/типы ----------

    private static Map<String, Method> getAllOverridableMethods(Class<?> superclass,
                                                                List<Class<?>> interfaces) {
        Map<String, Method> out = new LinkedHashMap<>();
        // Сначала иерархия классов (конкретные реализации побеждают interface-abstract),
        // включая Object (equals/hashCode/toString переопределять можно).
        Set<String> sealed = new HashSet<>();
        for (Class<?> c = superclass; c != null; c = c.getSuperclass()) {
            collectDeclared(c, out);
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isFinal(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) {
                    sealed.add(resolutionKey(m.getName(), m.getParameterTypes()));
                }
            }
        }
        // Затем интерфейсы (BFS по всей иерархии).
        ArrayDeque<Class<?>> queue = new ArrayDeque<>(interfaces);
        for (Class<?> c = superclass; c != null; c = c.getSuperclass()) {
            Collections.addAll(queue, c.getInterfaces());
        }
        while (!queue.isEmpty()) {
            Class<?> iface = queue.poll();
            collectDeclared(iface, out);
            Collections.addAll(queue, iface.getInterfaces());
        }
        out.keySet().removeAll(sealed);
        return out;
    }

    private static void collectDeclared(Class<?> cls, Map<String, Method> out) {
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || m.isBridge() || m.isSynthetic()) {
                continue;
            }
            if (!Modifier.isPublic(mod) && !Modifier.isProtected(mod)) {
                continue;
            }
            out.putIfAbsent(resolutionKey(m.getName(), m.getParameterTypes()), m);
        }
    }

    private static Constructor<?> selectConstructor(ClassRecord rec, String ctorSig, Object[] args)
            throws Exception {
        if (ctorSig != null && !ctorSig.isEmpty()) {
            Class<?>[] params = parseParameterTypes(ctorSig, rec.clazz.getClassLoader());
            return rec.clazz.getDeclaredConstructor(params);
        }
        for (Constructor<?> ctor : rec.clazz.getDeclaredConstructors()) {
            if (ctor.getParameterTypes().length == args.length
                    && argsCompatible(args, ctor.getParameterTypes())) {
                return ctor;
            }
        }
        return null;
    }

    private static boolean argsCompatible(Object[] args, Class<?>[] params) {
        for (int i = 0; i < params.length; i++) {
            Object a = args[i];
            if (a == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (params[i].isPrimitive()) {
                if (params[i] == boolean.class) {
                    if (!(a instanceof Boolean)) return false;
                } else if (params[i] == char.class) {
                    if (!(a instanceof Character) && !(a instanceof Number)
                            && !(a instanceof String && ((String) a).length() == 1)) return false;
                } else if (!(a instanceof Number)) {
                    return false;
                }
                continue;
            }
            if (params[i].isInstance(a)) {
                continue;
            }
            if (params[i] == String.class && a instanceof CharSequence) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static Object[] coerceArgs(Object[] args, Class<?>[] params) {
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            out[i] = i < args.length ? coerce(args[i], params[i]) : defaultValue(params[i]);
        }
        return out;
    }

    private static Object convertResult(PyObject result, Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return null;
        }
        if (result == null) {
            return defaultValue(returnType);
        }
        try {
            if (returnType.isPrimitive()) {
                return coerce(result.toJava(Object.class), returnType);
            }
            return result.toJava(returnType);
        } catch (Throwable t) {
            FileLog.e(TAG + ": cannot convert dispatch result to " + returnType.getName(), t);
            return defaultValue(returnType);
        }
    }

    private static Object executeMvel(MethodRecord mr, Object peer, Object proxy, Object[] args) {
        try {
            Map<String, Object> vars = new HashMap<>();
            vars.put("java", proxy);
            vars.put("proxy", proxy);
            vars.put("python", peer);
            vars.put("py", peer);
            vars.put("self", peer);
            vars.put("args", args);
            vars.put("argc", args.length);
            for (int i = 0; i < args.length; i++) {
                vars.put("arg" + i, args[i]);
            }
            Serializable compiled = MVEL_CACHE.computeIfAbsent(mr.mvel, MVEL::compileExpression);
            return coerce(MVEL.executeExpression(compiled, proxy, vars), mr.returnType);
        } catch (Throwable t) {
            FileLog.e(TAG + ": MVEL failed for " + mr.pyKey, t);
            return defaultValue(mr.returnType);
        }
    }

    static Object coerce(Object value, Class<?> type) {
        if (type == void.class || type == Void.class) {
            return null;
        }
        if (value == null || value == JSONObject.NULL) {
            return defaultValue(type);
        }
        if (type.isPrimitive()) {
            if (type == boolean.class) {
                if (value instanceof Boolean) return value;
                if (value instanceof Number) return ((Number) value).intValue() != 0;
                return Boolean.parseBoolean(String.valueOf(value));
            }
            if (type == char.class) {
                if (value instanceof Character) return value;
                if (value instanceof Number) return (char) ((Number) value).intValue();
                String s = String.valueOf(value);
                return s.isEmpty() ? (char) 0 : s.charAt(0);
            }
            Number n = value instanceof Number ? (Number) value : null;
            if (n == null && value instanceof Boolean) {
                n = (Boolean) value ? 1 : 0;
            }
            if (n == null) {
                try {
                    n = Double.parseDouble(String.valueOf(value));
                } catch (Exception e) {
                    return defaultValue(type);
                }
            }
            if (type == int.class) return n.intValue();
            if (type == long.class) return n.longValue();
            if (type == double.class) return n.doubleValue();
            if (type == float.class) return n.floatValue();
            if (type == short.class) return n.shortValue();
            if (type == byte.class) return n.byteValue();
            return defaultValue(type);
        }
        if (type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        return value;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0d;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return 0;
    }

    // ---------- имена/дескрипторы ----------

    private static String resolutionKey(String name, Class<?>[] params) {
        return name + "|" + paramsDescriptor(params);
    }

    private static String expandedKey(Method m) {
        return expandedKey(m.getName(), m.getParameterTypes(), m.getReturnType());
    }

    private static String expandedKey(String name, Class<?>[] params, Class<?> ret) {
        return name + "|" + paramsDescriptor(params) + descriptorOf(ret);
    }

    private static String paramsDescriptor(Class<?>[] params) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            sb.append(descriptorOf(p));
        }
        return sb.append(')').toString();
    }

    private static String descriptorOf(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            if (type == double.class) return "D";
        }
        if (type.isArray()) {
            return type.getName().replace('.', '/');
        }
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static Class<?> typeFromDescriptor(String desc, int[] pos, ClassLoader cl)
            throws ClassNotFoundException {
        char c = desc.charAt(pos[0]++);
        switch (c) {
            case 'V': return void.class;
            case 'Z': return boolean.class;
            case 'B': return byte.class;
            case 'C': return char.class;
            case 'S': return short.class;
            case 'I': return int.class;
            case 'J': return long.class;
            case 'F': return float.class;
            case 'D': return double.class;
            case 'L': {
                int semi = desc.indexOf(';', pos[0]);
                String name = desc.substring(pos[0], semi).replace('/', '.');
                pos[0] = semi + 1;
                return forName(name, cl);
            }
            case '[':
                return Array.newInstance(typeFromDescriptor(desc, pos, cl), 0).getClass();
            default:
                throw new ClassNotFoundException("bad descriptor: " + desc);
        }
    }

    private static Class<?>[] parseParameterTypes(String sig, ClassLoader cl) throws ClassNotFoundException {
        int open = sig.indexOf('(');
        int close = sig.indexOf(')', open);
        if (open < 0 || close < 0) {
            throw new ClassNotFoundException("bad method signature: " + sig);
        }
        List<Class<?>> out = new ArrayList<>();
        int[] pos = {open + 1};
        while (pos[0] < close) {
            out.add(typeFromDescriptor(sig, pos, cl));
        }
        return out.toArray(new Class<?>[0]);
    }

    private static Class<?> parseReturnType(String sig, ClassLoader cl) throws ClassNotFoundException {
        int close = sig.indexOf(')');
        if (close < 0) {
            throw new ClassNotFoundException("bad method signature: " + sig);
        }
        int[] pos = {close + 1};
        return typeFromDescriptor(sig, pos, cl);
    }

    private static Class<?> resolveTypeName(String name, ClassLoader cl) throws ClassNotFoundException {
        switch (name) {
            case "void": return void.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "char": return char.class;
            case "short": return short.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            default:
        }
        if (name.endsWith("[]")) {
            return Array.newInstance(resolveTypeName(name.substring(0, name.length() - 2), cl), 0).getClass();
        }
        if (name.startsWith("[")) {
            int[] pos = {0};
            return typeFromDescriptor(name, pos, cl);
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            int[] pos = {0};
            return typeFromDescriptor(name, pos, cl);
        }
        return forName(name, cl);
    }

    private static Class<?> forName(String name, ClassLoader cl) throws ClassNotFoundException {
        try {
            return Class.forName(name, false, cl);
        } catch (ClassNotFoundException e) {
            String n = name;
            int idx;
            while ((idx = n.lastIndexOf('.')) > 0) {
                n = n.substring(0, idx) + "$" + n.substring(idx + 1);
                try {
                    return Class.forName(n, false, cl);
                } catch (ClassNotFoundException ignore) {
                }
            }
            throw e;
        }
    }

    // ---------- утилиты ----------

    private static ClassLoader appClassLoader() {
        if (ApplicationLoader.applicationContext != null) {
            return ApplicationLoader.applicationContext.getClassLoader();
        }
        return ClassProxyFactory.class.getClassLoader();
    }

    private static String optString(JSONObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.isNull(key)) {
            return fallback;
        }
        return o.optString(key, fallback);
    }

    private static String sanitize(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return sb.toString();
    }

    private static String capitalize(String name) {
        switch (name) {
            case "int": return "Int";
            case "boolean": return "Bool";
            case "short": return "Short";
            case "byte": return "Byte";
            case "char": return "Char";
            default:
                return name.substring(0, 1).toUpperCase() + name.substring(1);
        }
    }

    private static TypeId<?>[] toTypeIds(Class<?>[] classes) {
        TypeId<?>[] out = new TypeId[classes.length];
        for (int i = 0; i < classes.length; i++) {
            out[i] = TypeId.get(classes[i]);
        }
        return out;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
