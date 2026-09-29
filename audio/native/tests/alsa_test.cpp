#include "pcm_audio.h"

#include <array>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <stdexcept>
#include <string>
#include <thread>

extern "C" void fake_alsa_reset(int);
extern "C" int fake_alsa_opens();
extern "C" int fake_alsa_closes();
extern "C" int fake_alsa_writes();
extern "C" int fake_alsa_recoveries();
extern "C" int fake_alsa_invalid_samples();

using namespace std::chrono_literals;

namespace {

void require(bool condition, const char *message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

int render(void *, float *samples, std::int32_t frames) {
    for (std::int32_t frame = 0; frame < frames; ++frame) {
        samples[frame * 2] = 0.25F;
        samples[frame * 2 + 1] = -1.0F;
    }
    return 1;
}

void check_playback(int mode) {
    fake_alsa_reset(mode);
    auto *output = pcm_audio_create(render, nullptr, nullptr, 0);
    require(output != nullptr, "ALSA output creation failed");
    require(pcm_audio_start(output) == 1, "ALSA output start failed");
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    while (fake_alsa_writes() < 8 && std::chrono::steady_clock::now() < deadline) {
        std::this_thread::sleep_for(1ms);
    }
    require(pcm_audio_stop(output) == 1, "ALSA output stop failed");
    std::array<char, 256> error{};
    require(pcm_audio_take_error(output, error.data(), error.size()) == 0,
            "Unexpected ALSA output error");
    require(fake_alsa_writes() >= 8, "ALSA did not process partial writes");
    require(fake_alsa_invalid_samples() == 0,
            "ALSA wrote incorrect samples or used a wrong sample format");
    require(fake_alsa_opens() == (mode == 1 ? 2 : 1),
            "ALSA fallback did not reopen the PCM device");
    require(fake_alsa_closes() == fake_alsa_opens(), "ALSA leaked a PCM device");
    require(fake_alsa_recoveries() == (mode == 2 ? 1 : 0), "ALSA did not recover the underrun");
    require(pcm_audio_destroy(output) == 1, "ALSA output destruction failed");
}

void check_unsupported_format() {
    fake_alsa_reset(3);
    auto *output = pcm_audio_create(render, nullptr, nullptr, 0);
    require(pcm_audio_start(output) == 1, "ALSA output start failed");
    const auto deadline = std::chrono::steady_clock::now() + 2s;
    std::array<char, 256> error{};
    bool found_error = false;
    while (std::chrono::steady_clock::now() < deadline) {
        if (pcm_audio_take_error(output, error.data(), error.size()) == 1) {
            found_error = true;
            break;
        }
        std::this_thread::sleep_for(1ms);
    }
    require(pcm_audio_destroy(output) == 1, "ALSA failed to close after a format error");
    require(found_error, "ALSA configuration failure was not reported");
    require(std::string(error.data()).find("neither") != std::string::npos,
            "ALSA configuration failure was not explained");
    require(fake_alsa_opens() == fake_alsa_closes(), "ALSA leaked a failed device");
}

} // namespace

int main() {
    try {
        check_playback(0);
        check_playback(1);
        check_playback(2);
        check_unsupported_format();
        std::cout << "ALSA backend checks passed\n";
        return EXIT_SUCCESS;
    } catch (const std::exception &error) {
        std::cerr << error.what() << '\n';
        return EXIT_FAILURE;
    }
}
