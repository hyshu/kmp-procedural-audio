#include "pcm_audio.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>

using namespace std::chrono_literals;

namespace {

struct RenderState {
    std::atomic_int calls = 0;
    std::atomic_bool invalid_buffer = false;
};

void require(bool condition, const char *message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

int render(void *context, float *samples, std::int32_t frames) {
    auto &state = *static_cast<RenderState *>(context);
    if (samples == nullptr || frames <= 0) {
        state.invalid_buffer.store(true);
        return 0;
    }
    std::fill_n(samples, static_cast<std::size_t>(frames) * 2, 0.0F);
    state.calls.fetch_add(1);
    return 1;
}

std::string wait_for_output(pcm_audio_output *output, RenderState &state, int previous_calls) {
    std::array<char, 1024> error{};
    const auto deadline = std::chrono::steady_clock::now() + 5s;
    while (state.calls.load() == previous_calls && std::chrono::steady_clock::now() < deadline) {
        if (pcm_audio_take_error(output, error.data(), error.size()) == 1) {
            return error.data();
        }
        std::this_thread::sleep_for(1ms);
    }
    require(state.calls.load() > previous_calls,
            "The backend produced neither PCM nor a device error");
    return {};
}

void require_stopped(RenderState &state) {
    const auto stopped_calls = state.calls.load();
    std::this_thread::sleep_for(10ms);
    require(state.calls.load() == stopped_calls,
            "The backend rendered after stop or destroy returned");
}

} // namespace

int main() {
    try {
        RenderState state;
        std::array<char, 1024> error{};
        std::unique_ptr<pcm_audio_output, decltype(&pcm_audio_destroy)> output(
            pcm_audio_create(render, &state, error.data(), error.size()), pcm_audio_destroy);
        if (!output) {
            throw std::runtime_error(std::string("Backend construction failed. ") + error.data());
        }
        require(pcm_audio_start(output.get()) == 1, "Backend start failed");
        require(pcm_audio_start(output.get()) == 1, "Repeated start failed");
        const auto initial_error = wait_for_output(output.get(), state, 0);
        require(pcm_audio_stop(output.get()) == 1, "Backend stop failed");
        require_stopped(state);

        const auto previous_calls = state.calls.load();
        require(pcm_audio_start(output.get()) == 1, "Backend restart failed");
        const auto restart_error = wait_for_output(output.get(), state, previous_calls);
        require(pcm_audio_stop(output.get()) == 1, "Backend stop after restart failed");
        require_stopped(state);
        require(pcm_audio_destroy(output.get()) == 1, "Backend destruction failed");
        output.release();
        require_stopped(state);
        require(!state.invalid_buffer.load(), "The backend requested an invalid PCM buffer");

        if (!initial_error.empty() || !restart_error.empty()) {
#ifdef _WIN32
            // Only a missing Windows audio endpoint is an allowed hosted-runner skip.
            // Other WASAPI failures still indicate a regression.
            const auto no_device = [](const std::string &message) {
                return message.empty() ||
                       (message.find("GetDefaultAudioEndpoint") != std::string::npos &&
                        message.find("0x80070490") != std::string::npos);
            };
            if (no_device(initial_error) && no_device(restart_error)) {
                std::cout << "WASAPI playback skipped because this runner has no audio endpoint\n";
                return 77;
            }
#endif
            throw std::runtime_error(initial_error.empty() ? restart_error : initial_error);
        }
        std::cout << "Host backend rendered PCM and completed pause, restart, and close\n";
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
