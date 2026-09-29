#ifndef PCM_AUDIO_BACKEND_H
#define PCM_AUDIO_BACKEND_H

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

namespace procedural_audio {

using RenderCallback = std::function<void(float *, std::int32_t)>;

class AudioBackend {
  public:
    virtual ~AudioBackend() = default;
    virtual void start() = 0;
    virtual void stop() noexcept = 0;
    virtual std::string take_error() = 0;
};

std::unique_ptr<AudioBackend> make_backend(RenderCallback render);

} // namespace procedural_audio

#endif
