// Compiled by test_native_account_bounds.py with verbatim production JNI functions.
// Fake managers observe dispatch only; NativeAccountSlotsTest.cpp tests allocation.
#include <array>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using jint = int32_t;
using jlong = int64_t;
using jboolean = uint8_t;
using jclass = void *;
using jstring = const char *;

// NATIVE_CAPACITY
// JAVA_CAPACITY

static void require(bool condition, const char *message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

struct JavaVM {};
static JavaVM vm;
static JavaVM *java = &vm;

struct JNIEnv {
    bool failClassLookup = false;
    bool pendingException = false;
    int classLookups = 0;
    int throws = 0;
    int deletedRefs = 0;
    int stringsRead = 0;
    int stringsReleased = 0;
    char exceptionToken = 0;
    std::string exceptionClass;
    std::map<jstring, int> borrowed;

    jclass FindClass(const char *name) {
        ++classLookups;
        exceptionClass = name;
        if (failClassLookup) {
            pendingException = true; // JNI preserves the class-lookup exception.
            return nullptr;
        }
        return &exceptionToken;
    }

    jint ThrowNew(jclass type, const char *message) {
        require(type == &exceptionToken, "ThrowNew received the wrong class");
        require(message != nullptr && *message != '\0', "Missing account error message");
        require(!pendingException, "ThrowNew replaced a pending lookup exception");
        ++throws;
        pendingException = true;
        return 0;
    }

    void DeleteLocalRef(jclass type) {
        require(type == &exceptionToken, "Deleted the wrong exception class reference");
        ++deletedRefs;
    }

    const char *GetStringUTFChars(jstring value, jboolean *) {
        require(!pendingException, "String acquisition after an account exception");
        require(value != nullptr, "Invalid account reached a poisoned JNI string");
        ++stringsRead;
        ++borrowed[value];
        return value;
    }

    void ReleaseStringUTFChars(jstring value, const char *chars) {
        auto entry = borrowed.find(value);
        require(entry != borrowed.end() && chars == value, "Unmatched JNI string release");
        ++stringsReleased;
        if (--entry->second == 0) {
            borrowed.erase(entry);
        }
    }
};

struct Delegate {
    inline static std::atomic<int> constructions{0};
    Delegate() { ++constructions; }
};

class ConnectionsManager {
public:
    inline static std::atomic<int> lookups{0};
    inline static std::atomic<int> vmRegistrations{0};
    inline static JavaVM *registeredVm = nullptr;
    inline static bool javaBuffers = false;
    std::atomic<int> initCalls{0};
    std::atomic<int> delegateInstalls{0};
    std::atomic<int> pingReads{0};
    std::atomic<int> languageWrites{0};
    std::atomic<Delegate *> delegate{nullptr};

    static ConnectionsManager &getInstance(int32_t slot);

    static void useJavaVM(JavaVM *value, bool buffers) {
        registeredVm = value;
        javaBuffers = buffers;
        ++vmRegistrations;
    }

    void setDelegate(Delegate *value) {
        require(value != nullptr, "Installed a null delegate");
        delegate.store(value);
        ++delegateInstalls;
    }

    template <typename... Args>
    bool init(Args &&...) {
        require(delegate.load() != nullptr, "Manager initialized before its delegate");
        ++initCalls;
        return true;
    }

    jint getCurrentPingTime() {
        ++pingReads;
        return 137;
    }

    void setLangCode(std::string value) {
        require(value == "ru", "JNI did not forward the language string");
        ++languageWrites;
    }
};

// Deliberately pre-existing test doubles: there is no native allocator under test here.
static std::array<ConnectionsManager, MAX_ACCOUNT_COUNT> managers;

ConnectionsManager &ConnectionsManager::getInstance(int32_t slot) {
    require(slot >= 0 && slot < MAX_ACCOUNT_COUNT, "Invalid account reached a native manager");
    ++lookups;
    return managers[slot];
}

// PRODUCTION_JNI_FUNCTIONS

static const std::array<jint, 4> invalidSlots = {
    -1, MAX_ACCOUNT_COUNT, std::numeric_limits<jint>::min(), std::numeric_limits<jint>::max()
};

static void rejected(const JNIEnv &env) {
    require(env.pendingException, "Invalid account did not leave a pending exception");
    require(env.classLookups == 1, "Invalid account did not look up its exception class once");
    require(env.exceptionClass == "java/lang/IllegalArgumentException", "Wrong account exception type");
    require(env.throws == (env.failClassLookup ? 0 : 1), "Unexpected number of ThrowNew calls");
    require(env.deletedRefs == (env.failClassLookup ? 0 : 1), "Exception local-reference leak");
    require(env.stringsRead == 0 && env.stringsReleased == 0, "Invalid account accessed JNI strings");
}

static void balanced(const JNIEnv &env) {
    require(!env.pendingException && env.classLookups == 0, "Valid account raised an exception");
    require(env.stringsRead == env.stringsReleased && env.borrowed.empty(), "JNI string lease leaked");
}

static void callInit(JNIEnv &env, jint slot, bool poisonStrings = false) {
    auto text = [poisonStrings](const char *value) { return poisonStrings ? nullptr : value; };
    init(&env, nullptr, slot, 1, 2, 3,
         text("model"), text("system"), text("app"), text("language"), text("system-language"),
         text("config"), text("log"), text("registration"), text("fingerprint"),
         text("installer"), text("package"), 3600, 123456789, true, false, true, 1, 2);
}

static void initializedOnly(const std::vector<jint> &slots) {
    for (jint slot = 0; slot < MAX_ACCOUNT_COUNT; ++slot) {
        bool expected = false;
        for (jint initialized : slots) {
            expected |= slot == initialized;
        }
        require(managers[slot].initCalls == (expected ? 1 : 0), "Initialization was repeated or hit another slot");
        require(managers[slot].delegateInstalls == (expected ? 1 : 0), "Delegate installation was repeated or hit another slot");
    }
}

static void guardRange() {
    for (jint slot = 0; slot < MAX_ACCOUNT_COUNT; ++slot) {
        JNIEnv env;
        require(checkAccountIndex(&env, slot), "Valid physical account slot rejected");
        balanced(env);
    }
    for (jint slot : invalidSlots) {
        JNIEnv env;
        require(!checkAccountIndex(&env, slot), "Out-of-range account accepted");
        rejected(env);
    }
    require(ConnectionsManager::lookups == 0, "Bounds checking touched native managers");
}

static void invalidWrappers(bool failClassLookup) {
    for (jint slot : invalidSlots) {
        JNIEnv guard;
        guard.failClassLookup = failClassLookup;
        require(!checkAccountIndex(&guard, slot), "Invalid account accepted after class lookup");
        rejected(guard);
        JNIEnv getter;
        getter.failClassLookup = failClassLookup;
        require(getCurrentPingTime(&getter, nullptr, slot) == 0, "Rejected getter returned a native result");
        rejected(getter);
        JNIEnv setter;
        setter.failClassLookup = failClassLookup;
        setLangCode(&setter, nullptr, slot, nullptr);
        rejected(setter);
        JNIEnv initializer;
        initializer.failClassLookup = failClassLookup;
        callInit(initializer, slot, true);
        rejected(initializer);
    }
    require(ConnectionsManager::lookups == 0, "Invalid JNI call reached a manager");
    require(Delegate::constructions == 0, "Invalid JNI call constructed a delegate");
    initializedOnly({});
}

static void validWrappers() {
    for (jint slot : {0, 9, 10, MAX_ACCOUNT_COUNT - 1}) {
        JNIEnv env;
        require(getCurrentPingTime(&env, nullptr, slot) == 137, "Valid getter was not dispatched");
        setLangCode(&env, nullptr, slot, "ru");
        balanced(env);
        require(managers[slot].pingReads == 1 && managers[slot].languageWrites == 1,
                "JNI wrapper dispatched to the wrong slot");
    }
    initializedOnly({});
}

static void setJavaDoesNotInitialize() {
    JNIEnv env;
    for (jboolean buffers : {jboolean{0}, jboolean{1}, jboolean{0}}) {
        setJava(&env, nullptr, buffers);
        require(ConnectionsManager::registeredVm == java, "VM registration lost the VM pointer");
        require(ConnectionsManager::javaBuffers == bool(buffers), "VM registration lost the buffer flag");
    }
    require(ConnectionsManager::vmRegistrations == 3, "VM registration was not forwarded");
    require(ConnectionsManager::lookups == 0, "setJava eagerly accessed account managers");
    require(Delegate::constructions == 0, "setJava eagerly constructed a delegate");
    initializedOnly({});
    balanced(env);
}

static void initRepeated() {
    const std::vector<jint> slots = {0, MAX_ACCOUNT_COUNT - 1};
    for (int repeat = 0; repeat < 4; ++repeat) {
        for (jint slot : slots) {
            JNIEnv env;
            callInit(env, slot);
            balanced(env);
        }
    }
    initializedOnly(slots);
    require(Delegate::constructions > 0, "Initialization never constructed a delegate");
}

static void initConcurrent(bool mixedSlots) {
    constexpr int count = 32;
    std::atomic<int> ready{0};
    std::atomic<bool> start{false};
    std::vector<std::thread> threads;
    for (int index = 0; index < count; ++index) {
        threads.emplace_back([&, index] {
            JNIEnv env; // JNI environments and local references belong to their calling thread.
            ++ready;
            while (!start.load()) {
                std::this_thread::yield();
            }
            callInit(env, mixedSlots && index % 2 == 0 ? 0 : MAX_ACCOUNT_COUNT - 1);
            balanced(env);
        });
    }
    while (ready.load() != count) {
        std::this_thread::yield();
    }
    start.store(true);
    for (auto &thread : threads) {
        thread.join();
    }
    initializedOnly(mixedSlots ? std::vector<jint>{0, MAX_ACCOUNT_COUNT - 1}
                               : std::vector<jint>{MAX_ACCOUNT_COUNT - 1});
}

int main(int argc, char **argv) {
    require(argc == 2, "Expected one scenario name");
    const std::string scenario = argv[1];
    if (scenario == "capacity") {
        JNIEnv env;
        require(getAccountCapacity(&env, nullptr) == JAVA_ACCOUNT_CAPACITY, "JNI/Java account capacity mismatch");
        require(getAccountCapacity(&env, nullptr) == MAX_ACCOUNT_COUNT, "JNI/native account capacity mismatch");
        require(ConnectionsManager::lookups == 0, "Capacity query accessed native managers");
        balanced(env);
    } else if (scenario == "guard-range") {
        guardRange();
    } else if (scenario == "guard-class-lookup-failure") {
        invalidWrappers(true);
    } else if (scenario == "invalid-wrappers") {
        invalidWrappers(false);
    } else if (scenario == "valid-wrappers") {
        validWrappers();
    } else if (scenario == "set-java") {
        setJavaDoesNotInitialize();
    } else if (scenario == "init-repeat") {
        initRepeated();
    } else if (scenario == "init-concurrent") {
        initConcurrent(false);
    } else if (scenario == "init-concurrent-slots") {
        initConcurrent(true);
    } else {
        require(false, "Unknown scenario");
    }
    std::cout << "PASS " << scenario << '\n';
}
