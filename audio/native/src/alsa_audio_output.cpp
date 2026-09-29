#include "alsa_audio_output.h"

#include "alsa_api.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <stdexcept>
#include <utility>
#include <vector>

namespace procedural_audio {

namespace {

constexpr std::uint32_t sample_rate = 48'000;
constexpr std::uint32_t channel_count = 2;
constexpr std::int32_t frames_per_chunk = 512;
constexpr unsigned int target_latency_microseconds = 50'000;
constexpr int wait_timeout_milliseconds = 50;

class PcmHandle final {
  public:
    explicit PcmHandle(const AlsaApi &api) : api_(api) {}
    ~PcmHandle() {
        reset();
    }

    PcmHandle(const PcmHandle &) = delete;
    PcmHandle &operator=(const PcmHandle &) = delete;

    PcmHandle(PcmHandle &&other) noexcept
        : api_(other.api_), handle_(std::exchange(other.handle_, nullptr)) {}

    PcmHandle &operator=(PcmHandle &&other) = delete;

    [[nodiscard]] AlsaPcm *get() const noexcept {
        return handle_;
    }

    [[nodiscard]] AlsaPcm **out() noexcept {
        reset();
        return &handle_;
    }

    void reset() noexcept {
        if (handle_ != nullptr) {
            api_.drop(handle_);
            api_.close(handle_);
            handle_ = nullptr;
        }
    }

  private:
    const AlsaApi &api_;
    AlsaPcm *handle_ = nullptr;
};

struct ConfiguredPcm {
    explicit ConfiguredPcm(const AlsaApi &api) : handle(api) {}
    PcmHandle handle;
    AlsaFormat format = AlsaFormat::Unknown;
};

[[nodiscard]] std::runtime_error alsa_error(const AlsaApi &api, const std::string &operation,
                                            const int error_code) {
    return std::runtime_error(operation + ": " + api.error(error_code));
}

[[nodiscard]] bool configure(const AlsaApi &api, AlsaPcm *const pcm,
                             const AlsaFormat format) noexcept {
    return api.set_params(pcm, format, AlsaAccess::Interleaved, channel_count, sample_rate, 1,
                          target_latency_microseconds) >= 0;
}

[[nodiscard]] ConfiguredPcm open_pcm(const AlsaApi &api) {
    ConfiguredPcm configured(api);
    int result = api.open(configured.handle.out(), "default", AlsaStream::Playback, 1);
    if (result < 0) {
        throw alsa_error(api, "snd_pcm_open(default)", result);
    }

    if (configure(api, configured.handle.get(), AlsaFormat::FloatLittleEndian)) {
        configured.format = AlsaFormat::FloatLittleEndian;
        return configured;
    }

    // snd_pcm_set_params() may leave the handle partially configured. Reopen
    // before the portable signed-16 fallback.
    configured.handle.reset();
    result = api.open(configured.handle.out(), "default", AlsaStream::Playback, 1);
    if (result < 0) {
        throw alsa_error(api, "snd_pcm_open(default) for S16 fallback", result);
    }
    if (!configure(api, configured.handle.get(), AlsaFormat::Signed16LittleEndian)) {
        throw std::runtime_error(
            "ALSA default device supports neither 48 kHz stereo float nor S16 playback");
    }
    configured.format = AlsaFormat::Signed16LittleEndian;
    return configured;
}

[[nodiscard]] std::int16_t float_to_s16(const float sample) noexcept {
    if (!std::isfinite(sample)) {
        return 0;
    }
    const auto clamped = std::clamp(sample, -1.0F, 1.0F);
    if (clamped <= -1.0F) {
        return std::numeric_limits<std::int16_t>::min();
    }
    return static_cast<std::int16_t>(std::lrint(clamped * 32'767.0F));
}

[[nodiscard]] bool recover_pcm(const AlsaApi &api, AlsaPcm *const pcm,
                               const int error_code) noexcept {
    if (error_code == -EAGAIN) {
        return true;
    }
    return api.recover(pcm, error_code, 1) >= 0;
}

} // namespace

AlsaAudioOutput::AlsaAudioOutput(RenderCallback render, ErrorCallback report_error)
    : render_(std::move(render)), report_error_(std::move(report_error)) {
    if (!render_) {
        throw std::invalid_argument("ALSA output requires a render callback");
    }
}

AlsaAudioOutput::~AlsaAudioOutput() {
    stop();
}

void AlsaAudioOutput::start() {
    std::lock_guard lock(lifecycle_mutex_);
    if (worker_.joinable()) {
        if (!finished_.load(std::memory_order_acquire)) {
            return;
        }
        worker_.join();
    }
    stop_requested_.store(false, std::memory_order_release);
    finished_.store(false, std::memory_order_release);
    worker_ = std::thread([this] { run(); });
}

void AlsaAudioOutput::stop() noexcept {
    std::thread worker;
    {
        std::lock_guard lock(lifecycle_mutex_);
        if (!worker_.joinable()) {
            running_.store(false, std::memory_order_release);
            return;
        }
        stop_requested_.store(true, std::memory_order_release);
        worker = std::move(worker_);
    }
    worker.join();
    running_.store(false, std::memory_order_release);
}

bool AlsaAudioOutput::running() const noexcept {
    return running_.load(std::memory_order_acquire);
}

void AlsaAudioOutput::run() noexcept {
    try {
        const AlsaApi api;
        auto pcm = open_pcm(api);
        std::vector<float> float_buffer(static_cast<std::size_t>(frames_per_chunk) * channel_count,
                                        0.0F);
        std::vector<std::int16_t> s16_buffer(float_buffer.size(), 0);
        running_.store(true, std::memory_order_release);

        while (!stop_requested_.load(std::memory_order_acquire)) {
            render_(float_buffer.data(), frames_per_chunk);
            if (pcm.format == AlsaFormat::Signed16LittleEndian) {
                std::transform(float_buffer.begin(), float_buffer.end(), s16_buffer.begin(),
                               float_to_s16);
            }

            unsigned long offset = 0;
            while (offset < static_cast<unsigned long>(frames_per_chunk) &&
                   !stop_requested_.load(std::memory_order_acquire)) {
                const auto remaining = static_cast<unsigned long>(frames_per_chunk) - offset;
                const void *samples = nullptr;
                if (pcm.format == AlsaFormat::FloatLittleEndian) {
                    samples =
                        float_buffer.data() + static_cast<std::size_t>(offset) * channel_count;
                } else {
                    samples = s16_buffer.data() + static_cast<std::size_t>(offset) * channel_count;
                }

                const auto written = api.write(pcm.handle.get(), samples, remaining);
                if (written > 0) {
                    offset += static_cast<unsigned long>(written);
                    continue;
                }
                if (written == 0 || written == -EAGAIN) {
                    const int wait_result = api.wait(pcm.handle.get(), wait_timeout_milliseconds);
                    if (wait_result < 0 && !recover_pcm(api, pcm.handle.get(), wait_result)) {
                        throw alsa_error(api, "snd_pcm_wait", wait_result);
                    }
                    continue;
                }
                const auto error_code = static_cast<int>(written);
                if (!recover_pcm(api, pcm.handle.get(), error_code)) {
                    throw alsa_error(api, "snd_pcm_writei", error_code);
                }
            }
        }
    } catch (const std::exception &error) {
        if (!stop_requested_.load(std::memory_order_acquire)) {
            publish_error(error.what());
        }
    } catch (...) {
        if (!stop_requested_.load(std::memory_order_acquire)) {
            publish_error("Unknown ALSA audio thread failure");
        }
    }
    running_.store(false, std::memory_order_release);
    finished_.store(true, std::memory_order_release);
}

void AlsaAudioOutput::publish_error(const std::string &message) noexcept {
    if (!report_error_) {
        return;
    }
    try {
        report_error_(message);
    } catch (...) {
        // Error reporting must never escape the audio thread.
    }
}

} // namespace procedural_audio
