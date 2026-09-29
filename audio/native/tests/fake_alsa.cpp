#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <new>
#include <thread>

namespace {
struct Pcm {
    int format = -1;
};
std::atomic_int mode = 0;
std::atomic_int opens = 0;
std::atomic_int closes = 0;
std::atomic_int writes = 0;
std::atomic_int recoveries = 0;
std::atomic_int invalid_samples = 0;
} // namespace

extern "C" void fake_alsa_reset(int requested_mode) {
    mode.store(requested_mode);
    opens.store(0);
    closes.store(0);
    writes.store(0);
    recoveries.store(0);
    invalid_samples.store(0);
}

extern "C" int fake_alsa_opens() {
    return opens.load();
}
extern "C" int fake_alsa_closes() {
    return closes.load();
}
extern "C" int fake_alsa_writes() {
    return writes.load();
}
extern "C" int fake_alsa_recoveries() {
    return recoveries.load();
}
extern "C" int fake_alsa_invalid_samples() {
    return invalid_samples.load();
}

extern "C" int snd_pcm_open(Pcm **pcm, const char *, int stream, int flags) {
    if (stream != 0 || flags != 1) {
        return -EINVAL;
    }
    *pcm = new (std::nothrow) Pcm();
    if (*pcm == nullptr) {
        return -ENOMEM;
    }
    opens.fetch_add(1);
    return 0;
}

extern "C" int snd_pcm_close(Pcm *pcm) {
    delete pcm;
    closes.fetch_add(1);
    return 0;
}

extern "C" int snd_pcm_drop(Pcm *) {
    return 0;
}

extern "C" int snd_pcm_set_params(Pcm *pcm, int format, int access, unsigned int channels,
                                  unsigned int rate, int resample, unsigned int latency) {
    if (access != 3 || channels != 2 || rate != 48000 || resample != 1 || latency != 50000) {
        return -EINVAL;
    }
    if (mode.load() == 3 || (mode.load() == 1 && format == 14)) {
        return -EINVAL;
    }
    pcm->format = format;
    return 0;
}

extern "C" long snd_pcm_writei(Pcm *pcm, const void *buffer, unsigned long frames) {
    const int call = writes.fetch_add(1);
    if (mode.load() == 2 && call == 0) {
        return -EPIPE;
    }
    const auto written = std::min(frames, 128UL);
    for (unsigned long frame = 0; frame < written; ++frame) {
        bool valid = false;
        if (pcm->format == 14) {
            const auto *samples = static_cast<const float *>(buffer);
            valid = samples[frame * 2] == 0.25F && samples[frame * 2 + 1] == -1.0F;
        } else if (pcm->format == 2) {
            const auto *samples = static_cast<const std::int16_t *>(buffer);
            valid = samples[frame * 2] == 8192 && samples[frame * 2 + 1] == -32768;
        }
        if (!valid) {
            invalid_samples.fetch_add(1);
        }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
    return static_cast<long>(written);
}

extern "C" int snd_pcm_wait(Pcm *, int) {
    return 1;
}
extern "C" int snd_pcm_recover(Pcm *, int, int) {
    recoveries.fetch_add(1);
    return 0;
}
extern "C" const char *snd_strerror(int) {
    return "Fake ALSA failure";
}
