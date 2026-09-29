#include "audio_backend.h"
#include "pcm_audio.h"

#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <future>
#include <iostream>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>

using namespace std::chrono_literals;

namespace {

std::atomic_bool fail_create = false;
std::atomic_bool fail_start = false;
std::atomic_int worker_count = 0;

void require(bool condition, const char *message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

class FakeBackend final : public procedural_audio::AudioBackend {
  public:
    explicit FakeBackend(procedural_audio::RenderCallback render) : render_(std::move(render)) {}
    ~FakeBackend() override {
        stop();
    }

    void start() override {
        if (fail_start.exchange(false)) {
            throw std::runtime_error("Fake device could not start");
        }
        if (worker_.joinable()) {
            return;
        }
        stopped_.store(false);
        worker_ = std::thread([this] {
            worker_count.fetch_add(1);
            std::array<float, 16> samples{};
            try {
                while (!stopped_.load()) {
                    render_(samples.data(), 8);
                    std::this_thread::sleep_for(1ms);
                }
            } catch (const std::exception &error) {
                std::lock_guard lock(error_mutex_);
                error_ = error.what();
            }
            worker_count.fetch_sub(1);
        });
    }

    void stop() noexcept override {
        stopped_.store(true);
        if (worker_.joinable()) {
            worker_.join();
        }
    }

    std::string take_error() override {
        std::lock_guard lock(error_mutex_);
        return std::exchange(error_, {});
    }

  private:
    procedural_audio::RenderCallback render_;
    std::atomic_bool stopped_ = true;
    std::thread worker_;
    std::mutex error_mutex_;
    std::string error_;
};

struct RenderState {
    std::atomic_int count = 0;
    std::atomic_bool fail = false;
    std::mutex mutex;
    std::condition_variable condition;
    bool block = false;
    bool entered = false;
    bool released = false;
};

int render(void *context, float *samples, std::int32_t frames) {
    auto &state = *static_cast<RenderState *>(context);
    state.count.fetch_add(1);
    {
        std::unique_lock lock(state.mutex);
        state.entered = true;
        state.condition.notify_all();
        if (state.block) {
            state.condition.wait(lock, [&state] { return state.released; });
        }
    }
    for (int index = 0; index < frames * 2; ++index) {
        samples[index] = 0.25F;
    }
    return state.fail.load() ? 0 : 1;
}

void wait_for_render(RenderState &state) {
    std::unique_lock lock(state.mutex);
    require(state.condition.wait_for(lock, 2s, [&state] { return state.entered; }),
            "Output thread never called render");
}

void test_create_errors() {
    std::array<char, 128> error{};
    require(pcm_audio_create(nullptr, nullptr, error.data(), error.size()) == nullptr,
            "An empty callback must be rejected");
    require(error[0] != '\0', "Creation failures must include a message");
    fail_create.store(true);
    RenderState state;
    require(pcm_audio_create(render, &state, error.data(), error.size()) == nullptr,
            "Backend construction failures must not cross the C ABI");
    require(std::string(error.data()) == "Fake backend construction failed",
            "The backend construction error was lost");
}

void test_start_stop_and_restart() {
    RenderState state;
    auto *output = pcm_audio_create(render, &state, nullptr, 0);
    require(output != nullptr, "Output creation failed");
    require(pcm_audio_start(output) == 1, "Start failed");
    require(pcm_audio_start(output) == 1, "Repeated start must succeed");
    wait_for_render(state);
    require(worker_count.load() == 1, "Repeated start created more than one worker");
    require(pcm_audio_stop(output) == 1, "Stop failed");
    require(worker_count.load() == 0, "Stop returned before joining its worker");
    const auto count = state.count.load();
    std::this_thread::sleep_for(10ms);
    require(state.count.load() == count, "Rendering continued after stop");
    require(pcm_audio_stop(output) == 1, "Repeated stop must succeed");
    {
        std::lock_guard lock(state.mutex);
        state.entered = false;
    }
    require(pcm_audio_start(output) == 1, "Restart failed");
    wait_for_render(state);
    require(state.count.load() > count, "Restart did not resume rendering");
    require(pcm_audio_destroy(output) == 1, "Destroy failed");
    require(worker_count.load() == 0, "Destroy did not join its worker");
}

void test_destroy_waits_for_callback() {
    RenderState state;
    state.block = true;
    auto *output = pcm_audio_create(render, &state, nullptr, 0);
    require(pcm_audio_start(output) == 1, "Start failed");
    wait_for_render(state);
    auto destruction =
        std::async(std::launch::async, [output] { return pcm_audio_destroy(output); });
    require(destruction.wait_for(20ms) == std::future_status::timeout,
            "Destroy returned while the callback still used user data");
    {
        std::lock_guard lock(state.mutex);
        state.released = true;
        state.condition.notify_all();
    }
    require(destruction.get() == 1, "Destroy failed after the callback completed");
    require(worker_count.load() == 0, "A worker survived destruction");
}

void test_errors_can_be_consumed() {
    RenderState state;
    auto *output = pcm_audio_create(render, &state, nullptr, 0);
    std::array<char, 128> error{};
    fail_start.store(true);
    require(pcm_audio_start(output) == 0, "A backend exception must become a start failure");
    require(pcm_audio_take_error(output, nullptr, 0) == 0, "An invalid error buffer was accepted");
    require(pcm_audio_take_error(output, error.data(), error.size()) == 1,
            "The synchronous failure was not retained");
    require(std::string(error.data()) == "Fake device could not start", "Wrong start error");
    require(pcm_audio_take_error(output, error.data(), error.size()) == 0,
            "Reading an error did not consume it");
    state.fail.store(true);
    require(pcm_audio_start(output) == 1, "Start failed");
    wait_for_render(state);
    require(pcm_audio_stop(output) == 1, "Stop failed");
    require(pcm_audio_take_error(output, error.data(), error.size()) == 1,
            "The asynchronous callback failure was lost");
    require(std::string(error.data()) == "Audio render callback failed", "Wrong callback error");
    require(pcm_audio_destroy(output) == 1, "Destroy failed");
}

struct RecursiveState {
    pcm_audio_output *output = nullptr;
    std::atomic_int result = -1;
};

int recursive_render(void *context, float *, std::int32_t) {
    auto &state = *static_cast<RecursiveState *>(context);
    state.result.store(pcm_audio_destroy(state.output));
    return 1;
}

void test_callback_cannot_destroy_output() {
    RecursiveState state;
    state.output = pcm_audio_create(recursive_render, &state, nullptr, 0);
    require(pcm_audio_start(state.output) == 1, "Start failed");
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (state.result.load() == -1 && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(1ms);
    }
    require(state.result.load() == 0, "A callback must not destroy its own output");
    require(pcm_audio_destroy(state.output) == 1, "Owner could not destroy output");
}

} // namespace

namespace procedural_audio {

std::unique_ptr<AudioBackend> make_backend(RenderCallback render) {
    if (fail_create.exchange(false)) {
        throw std::runtime_error("Fake backend construction failed");
    }
    return std::make_unique<FakeBackend>(std::move(render));
}

} // namespace procedural_audio

int main() {
    try {
        test_create_errors();
        test_start_stop_and_restart();
        test_destroy_waits_for_callback();
        test_errors_can_be_consumed();
        test_callback_cannot_destroy_output();
        std::cout << "Native audio lifecycle checks passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
