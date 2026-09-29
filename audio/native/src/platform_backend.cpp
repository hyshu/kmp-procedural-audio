#include "audio_backend.h"

#include <mutex>
#include <utility>

#ifdef _WIN32
#include "wasapi_output.h"
#include <windows.h>
#else
#include "alsa_audio_output.h"
#endif

namespace procedural_audio {
namespace {

#ifdef _WIN32

class PlatformBackend final : public AudioBackend {
  public:
    explicit PlatformBackend(RenderCallback render) : render_(std::move(render)) {}

    void start() override {
        output_.Start(render_);
    }
    void stop() noexcept override {
        output_.Stop();
    }

    std::string take_error() override {
        const auto wide = output_.ConsumeError();
        if (wide.empty()) {
            return {};
        }
        const int length = WideCharToMultiByte(
            CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), nullptr, 0, nullptr, nullptr);
        if (length <= 0) {
            return "WASAPI output failed";
        }
        std::string result(static_cast<std::size_t>(length), '\0');
        WideCharToMultiByte(CP_UTF8, 0, wide.data(), static_cast<int>(wide.size()), result.data(),
                            length, nullptr, nullptr);
        return result;
    }

  private:
    RenderCallback render_;
    WasapiOutput output_;
};

#else

class PlatformBackend final : public AudioBackend {
  public:
    explicit PlatformBackend(RenderCallback render)
        : output_(std::move(render), [this](std::string message) {
              std::lock_guard lock(error_mutex_);
              error_ = std::move(message);
          }) {}

    ~PlatformBackend() override {
        output_.stop();
    }
    void start() override {
        output_.start();
    }
    void stop() noexcept override {
        output_.stop();
    }

    std::string take_error() override {
        std::lock_guard lock(error_mutex_);
        return std::exchange(error_, {});
    }

  private:
    std::mutex error_mutex_;
    std::string error_;
    AlsaAudioOutput output_;
};

#endif

} // namespace

std::unique_ptr<AudioBackend> make_backend(RenderCallback render) {
    return std::make_unique<PlatformBackend>(std::move(render));
}

} // namespace procedural_audio
