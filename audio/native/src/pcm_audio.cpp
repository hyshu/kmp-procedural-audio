#include "pcm_audio.h"
#include "audio_backend.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace {

thread_local pcm_audio_output *current_callback = nullptr;

void copy_error(char *target, std::size_t capacity, const char *message) noexcept {
    if (target == nullptr || capacity == 0) {
        return;
    }
    const auto length = std::min(capacity - 1, std::strlen(message));
    std::memcpy(target, message, length);
    target[length] = '\0';
}

class CallbackScope final {
  public:
    explicit CallbackScope(pcm_audio_output *output) noexcept : previous_(current_callback) {
        current_callback = output;
    }
    ~CallbackScope() {
        current_callback = previous_;
    }

  private:
    pcm_audio_output *previous_;
};

} // namespace

struct pcm_audio_output {
    std::unique_ptr<procedural_audio::AudioBackend> backend;
    std::mutex lifecycle_mutex;
    std::mutex error_mutex;
    std::array<char, 1024> error{};

    void store_error(const char *message) noexcept {
        try {
            std::lock_guard lock(error_mutex);
            copy_error(error.data(), error.size(), message);
        } catch (...) {
            // Reporting must never let an exception cross the C boundary.
        }
    }
};

extern "C" pcm_audio_output *pcm_audio_create(const pcm_audio_render_fn render, void *user_data,
                                              char *error, const std::size_t error_capacity) {
    copy_error(error, error_capacity, "");
    try {
        if (render == nullptr) {
            throw std::invalid_argument("Audio output requires a render callback");
        }
        auto output = std::make_unique<pcm_audio_output>();
        auto *pointer = output.get();
        output->backend = procedural_audio::make_backend(
            [pointer, render, user_data](float *samples, std::int32_t frames) {
                CallbackScope scope(pointer);
                if (render(user_data, samples, frames) != 1) {
                    throw std::runtime_error("Audio render callback failed");
                }
            });
        return output.release();
    } catch (const std::exception &failure) {
        copy_error(error, error_capacity, failure.what());
    } catch (...) {
        copy_error(error, error_capacity, "Unknown audio output creation failure");
    }
    return nullptr;
}

extern "C" int pcm_audio_start(pcm_audio_output *output) {
    if (output == nullptr) {
        return 0;
    }
    if (current_callback == output) {
        output->store_error("Audio output cannot start inside its render callback");
        return 0;
    }
    try {
        std::lock_guard lock(output->lifecycle_mutex);
        output->backend->start();
        return 1;
    } catch (const std::exception &failure) {
        output->store_error(failure.what());
    } catch (...) {
        output->store_error("Unknown audio output start failure");
    }
    return 0;
}

extern "C" int pcm_audio_stop(pcm_audio_output *output) {
    if (output == nullptr) {
        return 1;
    }
    if (current_callback == output) {
        output->store_error("Audio output cannot stop inside its render callback");
        return 0;
    }
    try {
        std::lock_guard lock(output->lifecycle_mutex);
        output->backend->stop();
        return 1;
    } catch (const std::exception &failure) {
        output->store_error(failure.what());
    } catch (...) {
        output->store_error("Unknown audio output stop failure");
    }
    return 0;
}

extern "C" int pcm_audio_destroy(pcm_audio_output *output) {
    if (output == nullptr) {
        return 1;
    }
    if (pcm_audio_stop(output) != 1) {
        return 0;
    }
    // No native callback can access output or user_data after stop has joined.
    delete output;
    return 1;
}

extern "C" int pcm_audio_take_error(pcm_audio_output *output, char *error,
                                    const std::size_t error_capacity) {
    copy_error(error, error_capacity, "");
    if (output == nullptr || error == nullptr || error_capacity == 0) {
        return 0;
    }
    try {
        auto backend_error = output->backend->take_error();
        if (!backend_error.empty()) {
            output->store_error(backend_error.c_str());
        }
        std::lock_guard lock(output->error_mutex);
        if (output->error[0] == '\0') {
            return 0;
        }
        copy_error(error, error_capacity, output->error.data());
        output->error[0] = '\0';
        return 1;
    } catch (const std::exception &failure) {
        copy_error(error, error_capacity, failure.what());
    } catch (...) {
        copy_error(error, error_capacity, "Unknown audio error retrieval failure");
    }
    return 1;
}
