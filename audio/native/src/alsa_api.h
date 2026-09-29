#ifndef PCM_AUDIO_ALSA_API_H
#define PCM_AUDIO_ALSA_API_H

#include <cstring>
#include <dlfcn.h>
#include <stdexcept>
#include <string>

namespace procedural_audio {

// The small PCM ABI used here is stable in libasound.so.2.
// Its declarations are documented by the ALSA project in include/pcm.h.
struct AlsaPcm;
enum class AlsaStream : int { Playback = 0 };
enum class AlsaAccess : int { Interleaved = 3 };
enum class AlsaFormat : int { Unknown = -1, Signed16LittleEndian = 2, FloatLittleEndian = 14 };

class AlsaApi final {
  public:
    AlsaApi() {
        library_ = dlopen("libasound.so.2", RTLD_NOW | RTLD_LOCAL);
        if (library_ == nullptr) {
            throw std::runtime_error("ALSA is unavailable. Install the system libasound package.");
        }
        try {
            load(open, "snd_pcm_open");
            load(close, "snd_pcm_close");
            load(drop, "snd_pcm_drop");
            load(set_params, "snd_pcm_set_params");
            load(write, "snd_pcm_writei");
            load(wait, "snd_pcm_wait");
            load(recover, "snd_pcm_recover");
            load(error, "snd_strerror");
        } catch (...) {
            dlclose(library_);
            throw;
        }
    }

    ~AlsaApi() {
        dlclose(library_);
    }
    AlsaApi(const AlsaApi &) = delete;
    AlsaApi &operator=(const AlsaApi &) = delete;

    int (*open)(AlsaPcm **, const char *, AlsaStream, int) = nullptr;
    int (*close)(AlsaPcm *) = nullptr;
    int (*drop)(AlsaPcm *) = nullptr;
    int (*set_params)(AlsaPcm *, AlsaFormat, AlsaAccess, unsigned int, unsigned int, int,
                      unsigned int) = nullptr;
    long (*write)(AlsaPcm *, const void *, unsigned long) = nullptr;
    int (*wait)(AlsaPcm *, int) = nullptr;
    int (*recover)(AlsaPcm *, int, int) = nullptr;
    const char *(*error)(int) = nullptr;

  private:
    template <typename Function> void load(Function &function, const char *name) {
        void *symbol = dlsym(library_, name);
        if (symbol == nullptr) {
            throw std::runtime_error(std::string("ALSA entry point is unavailable ") + name);
        }
        static_assert(sizeof(symbol) == sizeof(function));
        std::memcpy(&function, &symbol, sizeof(function));
    }

    void *library_ = nullptr;
};

} // namespace procedural_audio

#endif
