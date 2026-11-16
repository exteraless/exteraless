#include <array>
#include <atomic>
#include <cassert>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>
#include <vector>
#include <pthread.h>
#include "Defines.h"

// Only the constructor is replaced: the allocator below is production code.
class ConnectionsManager {
public:
    static std::atomic<int> constructed;
    const int32_t account;
    explicit ConnectionsManager(int32_t value) : account(value) { ++constructed; }
    static ConnectionsManager& getInstance(int32_t instanceNum);
};

std::atomic<int> ConnectionsManager::constructed{0};

// NATIVE_ALLOCATOR

static void check(bool value, const char* message) {
    if (!value) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

int main() {
    static_assert(MAX_ACCOUNT_COUNT == 100, "Native and Java storage ceilings must be 100");
    check(ConnectionsManager::constructed == 0, "Native allocator eagerly constructed accounts");
    auto* last = &ConnectionsManager::getInstance(99);
    check(last->account == 99, "Slot 99 aliases a lower native account");
    check(ConnectionsManager::constructed == 1, "Accessing slot 99 constructed inactive runtimes");
    check(last == &ConnectionsManager::getInstance(99), "Native slot identity changed");

    // First concurrent access must publish a single instance for this slot.
    std::array<ConnectionsManager*, 8> sameSlot{};
    std::vector<std::thread> threads;
    for (std::size_t i = 0; i < sameSlot.size(); ++i) {
        threads.emplace_back([&, i] { sameSlot[i] = &ConnectionsManager::getInstance(42); });
    }
    for (auto& thread : threads) thread.join();
    for (auto* item : sameSlot) check(item == sameSlot[0], "Concurrent access duplicated a native slot");
    check(ConnectionsManager::constructed == 2, "Native first access is not lazy/thread-safe");

    std::array<ConnectionsManager*, MAX_ACCOUNT_COUNT> instances{};
    for (int i = 0; i < MAX_ACCOUNT_COUNT; ++i) {
        instances[i] = &ConnectionsManager::getInstance(i);
        check(instances[i]->account == i, "Native account ID changed");
        for (int j = 0; j < i; ++j) check(instances[i] != instances[j], "Native slots alias each other");
    }
    check(ConnectionsManager::constructed == 100, "Expected one native runtime per used slot");
    std::cout << "NativeAccountSlots: all 100 slots are distinct, lazy and thread-safe\n";
}
