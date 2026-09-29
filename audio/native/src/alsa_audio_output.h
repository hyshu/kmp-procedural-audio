#ifndef PCM_AUDIO_ALSA_AUDIO_OUTPUT_H
#define PCM_AUDIO_ALSA_AUDIO_OUTPUT_H

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace procedural_audio {

class AlsaAudioOutput final {
  public:
    using RenderCallback = std::function<void(float *, std::int32_t)>;
    using ErrorCallback = std::function<void(std::string)>;

    AlsaAudioOutput(RenderCallback render, ErrorCallback report_error);
    ~AlsaAudioOutput();

    AlsaAudioOutput(const AlsaAudioOutput &) = delete;
    AlsaAudioOutput &operator=(const AlsaAudioOutput &) = delete;
    AlsaAudioOutput(AlsaAudioOutput &&) = delete;
    AlsaAudioOutput &operator=(AlsaAudioOutput &&) = delete;

    void start();
    void stop() noexcept;
    [[nodiscard]] bool running() const noexcept;

  private:
    void run() noexcept;
    void publish_error(const std::string &message) noexcept;

    RenderCallback render_;
    ErrorCallback report_error_;
    mutable std::mutex lifecycle_mutex_;
    std::thread worker_;
    std::atomic_bool stop_requested_ = false;
    std::atomic_bool finished_ = true;
    std::atomic_bool running_ = false;
};

} // namespace procedural_audio

#endif
