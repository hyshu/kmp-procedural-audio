#ifndef PCM_AUDIO_WASAPI_OUTPUT_H
#define PCM_AUDIO_WASAPI_OUTPUT_H

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace procedural_audio {

class WasapiOutput final {
  public:
    using RenderCallback = std::function<void(float *, std::int32_t)>;

    WasapiOutput();
    ~WasapiOutput();

    WasapiOutput(const WasapiOutput &) = delete;
    WasapiOutput &operator=(const WasapiOutput &) = delete;
    WasapiOutput(WasapiOutput &&) = delete;
    WasapiOutput &operator=(WasapiOutput &&) = delete;

    void Start(RenderCallback callback);
    void Stop() noexcept;

    [[nodiscard]] std::wstring ConsumeError();

  private:
    class Impl;
    std::unique_ptr<Impl> implementation_;
};

} // namespace procedural_audio

#endif
