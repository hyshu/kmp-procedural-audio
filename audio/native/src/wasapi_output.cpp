#include "wasapi_output.h"

#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif

#include <windows.h>

#include <audioclient.h>
#include <avrt.h>
#include <initguid.h>
#include <ks.h>
#include <ksmedia.h>
#include <mmdeviceapi.h>

#include <array>
#include <chrono>
#include <climits>
#include <cstring>
#include <cwchar>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <string>
#include <system_error>
#include <thread>
#include <utility>

namespace procedural_audio {
namespace {

constexpr std::uint32_t kSampleRate = 48'000;
constexpr std::uint16_t kChannelCount = 2;
constexpr std::uint16_t kBitsPerSample = 32;
constexpr DWORD kRetryDelayMilliseconds = 750;
constexpr DWORD kStreamFlagAutoConvertPcm = 0x80000000UL;
constexpr DWORD kStreamFlagSrcDefaultQuality = 0x08000000UL;
constexpr GUID kIeeeFloatSubFormat{
    0x00000003,
    0x0000,
    0x0010,
    {0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71},
};

void SetCurrentThreadDescription() noexcept {
    const HMODULE kernel = GetModuleHandleW(L"kernel32.dll");
    if (kernel == nullptr) {
        return;
    }

    using SetThreadDescriptionFunction = HRESULT(WINAPI *)(HANDLE, PCWSTR);
    const FARPROC procedure = GetProcAddress(kernel, "SetThreadDescription");
    static_assert(sizeof(procedure) == sizeof(SetThreadDescriptionFunction));
    SetThreadDescriptionFunction set_thread_description = nullptr;
    std::memcpy(&set_thread_description, &procedure, sizeof(set_thread_description));
    if (set_thread_description != nullptr) {
        static_cast<void>(
            set_thread_description(GetCurrentThread(), L"Procedural audio WASAPI output"));
    }
}

class UniqueHandle final {
  public:
    UniqueHandle() noexcept = default;
    explicit UniqueHandle(HANDLE value) noexcept : value_(value) {}

    ~UniqueHandle() {
        Reset();
    }

    UniqueHandle(const UniqueHandle &) = delete;
    UniqueHandle &operator=(const UniqueHandle &) = delete;

    UniqueHandle(UniqueHandle &&other) noexcept : value_(std::exchange(other.value_, nullptr)) {}

    UniqueHandle &operator=(UniqueHandle &&other) noexcept {
        if (this != &other) {
            Reset(std::exchange(other.value_, nullptr));
        }
        return *this;
    }

    void Reset(HANDLE replacement = nullptr) noexcept {
        if (value_ != nullptr && value_ != INVALID_HANDLE_VALUE) {
            CloseHandle(value_);
        }
        value_ = replacement;
    }

    [[nodiscard]] HANDLE Get() const noexcept {
        return value_;
    }
    [[nodiscard]] explicit operator bool() const noexcept {
        return value_ != nullptr && value_ != INVALID_HANDLE_VALUE;
    }

  private:
    HANDLE value_ = nullptr;
};

template <typename Interface> class ComPtr final {
  public:
    ComPtr() noexcept = default;

    ~ComPtr() {
        Reset();
    }

    ComPtr(const ComPtr &) = delete;
    ComPtr &operator=(const ComPtr &) = delete;

    ComPtr(ComPtr &&other) noexcept : pointer_(std::exchange(other.pointer_, nullptr)) {}

    ComPtr &operator=(ComPtr &&other) noexcept {
        if (this != &other) {
            Reset(std::exchange(other.pointer_, nullptr));
        }
        return *this;
    }

    void Reset(Interface *replacement = nullptr) noexcept {
        if (pointer_ != nullptr) {
            pointer_->Release();
        }
        pointer_ = replacement;
    }

    [[nodiscard]] Interface **Put() noexcept {
        Reset();
        return &pointer_;
    }

    [[nodiscard]] Interface *Get() const noexcept {
        return pointer_;
    }
    [[nodiscard]] Interface *operator->() const noexcept {
        return pointer_;
    }

  private:
    Interface *pointer_ = nullptr;
};

class HResultError final : public std::runtime_error {
  public:
    HResultError(const HRESULT result, const char *const operation)
        : std::runtime_error(operation), result_(result) {}

    [[nodiscard]] HRESULT Result() const noexcept {
        return result_;
    }

  private:
    HRESULT result_;
};

void ThrowIfFailed(const HRESULT result, const char *const operation) {
    if (FAILED(result)) {
        throw HResultError(result, operation);
    }
}

[[nodiscard]] std::wstring Utf8ToWide(const std::string &text) {
    if (text.empty()) {
        return {};
    }

    const int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(),
                                           static_cast<int>(text.size()), nullptr, 0);
    if (length <= 0) {
        return std::wstring(text.begin(), text.end());
    }

    std::wstring result(static_cast<std::size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, text.data(), static_cast<int>(text.size()),
                        result.data(), length);
    return result;
}

[[nodiscard]] std::wstring DescribeHResult(const HRESULT result, const std::string &operation) {
    wchar_t *message = nullptr;
    const DWORD length = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, static_cast<DWORD>(result), MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT),
        reinterpret_cast<wchar_t *>(&message), 0, nullptr);

    std::wstring description = Utf8ToWide(operation);
    description.append(L" failed (0x");
    std::array<wchar_t, 9> hexadecimal{};
    std::swprintf(hexadecimal.data(), hexadecimal.size(), L"%08X",
                  static_cast<unsigned int>(result));
    description.append(hexadecimal.data());
    description.push_back(L')');

    if (length != 0 && message != nullptr) {
        std::wstring system_message(message, length);
        while (!system_message.empty() &&
               (system_message.back() == L'\r' || system_message.back() == L'\n')) {
            system_message.pop_back();
        }
        if (!system_message.empty()) {
            description.append(L": ");
            description.append(system_message);
        }
    }
    if (message != nullptr) {
        LocalFree(message);
    }
    return description;
}

class ComApartment final {
  public:
    ComApartment() {
        result_ = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        ThrowIfFailed(result_, "CoInitializeEx");
    }

    ~ComApartment() {
        if (SUCCEEDED(result_)) {
            CoUninitialize();
        }
    }

    ComApartment(const ComApartment &) = delete;
    ComApartment &operator=(const ComApartment &) = delete;

  private:
    HRESULT result_ = E_FAIL;
};

class MmcssRegistration final {
  public:
    MmcssRegistration() noexcept {
        DWORD task_index = 0;
        handle_ = AvSetMmThreadCharacteristicsW(L"Pro Audio", &task_index);
    }

    ~MmcssRegistration() {
        if (handle_ != nullptr) {
            AvRevertMmThreadCharacteristics(handle_);
        }
    }

    MmcssRegistration(const MmcssRegistration &) = delete;
    MmcssRegistration &operator=(const MmcssRegistration &) = delete;

  private:
    HANDLE handle_ = nullptr;
};

class AudioClientStopGuard final {
  public:
    explicit AudioClientStopGuard(IAudioClient *client) noexcept : client_(client) {}

    ~AudioClientStopGuard() {
        if (client_ != nullptr) {
            client_->Stop();
        }
    }

    AudioClientStopGuard(const AudioClientStopGuard &) = delete;
    AudioClientStopGuard &operator=(const AudioClientStopGuard &) = delete;

  private:
    IAudioClient *client_;
};

[[nodiscard]] WAVEFORMATEXTENSIBLE MakeOutputFormat() noexcept {
    WAVEFORMATEXTENSIBLE format{};
    format.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    format.Format.nChannels = kChannelCount;
    format.Format.nSamplesPerSec = kSampleRate;
    format.Format.wBitsPerSample = kBitsPerSample;
    format.Format.nBlockAlign = static_cast<WORD>(kChannelCount * (kBitsPerSample / CHAR_BIT));
    format.Format.nAvgBytesPerSec = format.Format.nSamplesPerSec * format.Format.nBlockAlign;
    format.Format.cbSize = static_cast<WORD>(sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX));
    format.Samples.wValidBitsPerSample = kBitsPerSample;
    format.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
    format.SubFormat = kIeeeFloatSubFormat;
    return format;
}

} // namespace

class WasapiOutput::Impl final {
  public:
    Impl() : stop_event_(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {
        if (!stop_event_) {
            throw std::system_error(static_cast<int>(GetLastError()), std::system_category(),
                                    "CreateEventW(stop)");
        }
    }

    ~Impl() {
        Stop();
    }

    void Start(RenderCallback callback) {
        if (!callback) {
            throw std::invalid_argument("WASAPI render callback is empty");
        }
        if (thread_.joinable()) {
            return;
        }

        callback_ = std::move(callback);
        if (!ResetEvent(stop_event_.Get())) {
            throw std::system_error(static_cast<int>(GetLastError()), std::system_category(),
                                    "ResetEvent(stop)");
        }
        thread_ = std::thread([this] { ThreadMain(); });
    }

    void Stop() noexcept {
        if (!thread_.joinable()) {
            return;
        }
        SetEvent(stop_event_.Get());
        thread_.join();
        thread_ = std::thread{};
        callback_ = {};
    }

    [[nodiscard]] std::wstring ConsumeError() {
        std::scoped_lock lock(error_mutex_);
        return std::exchange(error_, {});
    }

  private:
    void ThreadMain() noexcept {
        SetCurrentThreadDescription();
        try {
            [[maybe_unused]] ComApartment apartment;
            [[maybe_unused]] MmcssRegistration mmcss;

            while (WaitForSingleObject(stop_event_.Get(), 0) != WAIT_OBJECT_0) {
                try {
                    RunSession();
                } catch (const HResultError &error) {
                    StoreError(DescribeHResult(error.Result(), error.what()));
                } catch (const std::exception &error) {
                    StoreError(Utf8ToWide(error.what()));
                }

                if (WaitForSingleObject(stop_event_.Get(), kRetryDelayMilliseconds) ==
                    WAIT_OBJECT_0) {
                    break;
                }
            }
        } catch (const HResultError &error) {
            StoreError(DescribeHResult(error.Result(), error.what()));
        } catch (const std::exception &error) {
            StoreError(Utf8ToWide(error.what()));
        }
    }

    void RunSession() {
        ComPtr<IMMDeviceEnumerator> enumerator;
        ThrowIfFailed(CoCreateInstance(CLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                       IID_IMMDeviceEnumerator,
                                       reinterpret_cast<void **>(enumerator.Put())),
                      "CoCreateInstance(MMDeviceEnumerator)");

        ComPtr<IMMDevice> device;
        ThrowIfFailed(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, device.Put()),
                      "IMMDeviceEnumerator::GetDefaultAudioEndpoint");

        ComPtr<IAudioClient> audio_client;
        ThrowIfFailed(device->Activate(IID_IAudioClient, CLSCTX_ALL, nullptr,
                                       reinterpret_cast<void **>(audio_client.Put())),
                      "IMMDevice::Activate(IAudioClient)");

        const WAVEFORMATEXTENSIBLE output_format = MakeOutputFormat();
        constexpr DWORD stream_flags = static_cast<DWORD>(AUDCLNT_STREAMFLAGS_EVENTCALLBACK) |
                                       kStreamFlagAutoConvertPcm | kStreamFlagSrcDefaultQuality;
        ThrowIfFailed(audio_client->Initialize(AUDCLNT_SHAREMODE_SHARED, stream_flags, 0, 0,
                                               &output_format.Format, nullptr),
                      "IAudioClient::Initialize");

        UniqueHandle audio_event(CreateEventW(nullptr, FALSE, FALSE, nullptr));
        if (!audio_event) {
            throw std::system_error(static_cast<int>(GetLastError()), std::system_category(),
                                    "CreateEventW(audio)");
        }
        ThrowIfFailed(audio_client->SetEventHandle(audio_event.Get()),
                      "IAudioClient::SetEventHandle");

        UINT32 buffer_frame_count = 0;
        ThrowIfFailed(audio_client->GetBufferSize(&buffer_frame_count),
                      "IAudioClient::GetBufferSize");
        if (buffer_frame_count > static_cast<UINT32>(std::numeric_limits<std::int32_t>::max())) {
            throw std::runtime_error("WASAPI buffer is too large");
        }

        ComPtr<IAudioRenderClient> render_client;
        ThrowIfFailed(audio_client->GetService(IID_IAudioRenderClient,
                                               reinterpret_cast<void **>(render_client.Put())),
                      "IAudioClient::GetService(IAudioRenderClient)");

        FillBuffer(render_client.Get(), buffer_frame_count);
        ThrowIfFailed(audio_client->Start(), "IAudioClient::Start");
        [[maybe_unused]] AudioClientStopGuard stop_guard(audio_client.Get());

        const std::array<HANDLE, 2> events{
            stop_event_.Get(),
            audio_event.Get(),
        };
        while (true) {
            const DWORD wait_result = WaitForMultipleObjects(static_cast<DWORD>(events.size()),
                                                             events.data(), FALSE, INFINITE);
            if (wait_result == WAIT_OBJECT_0) {
                return;
            }
            if (wait_result != WAIT_OBJECT_0 + 1) {
                throw std::system_error(static_cast<int>(GetLastError()), std::system_category(),
                                        "WaitForMultipleObjects(WASAPI)");
            }

            UINT32 padding = 0;
            ThrowIfFailed(audio_client->GetCurrentPadding(&padding),
                          "IAudioClient::GetCurrentPadding");
            if (padding > buffer_frame_count) {
                throw std::runtime_error("WASAPI reported invalid buffer padding");
            }
            const UINT32 available_frames = buffer_frame_count - padding;
            if (available_frames != 0) {
                FillBuffer(render_client.Get(), available_frames);
            }
        }
    }

    void FillBuffer(IAudioRenderClient *const render_client, const UINT32 frame_count) {
        BYTE *bytes = nullptr;
        ThrowIfFailed(render_client->GetBuffer(frame_count, &bytes),
                      "IAudioRenderClient::GetBuffer");

        DWORD release_flags = 0;
        try {
            callback_(reinterpret_cast<float *>(bytes), static_cast<std::int32_t>(frame_count));
        } catch (const std::exception &error) {
            std::memset(bytes, 0,
                        static_cast<std::size_t>(frame_count) * kChannelCount * sizeof(float));
            release_flags = AUDCLNT_BUFFERFLAGS_SILENT;
            StoreError(Utf8ToWide(error.what()));
        } catch (...) {
            std::memset(bytes, 0,
                        static_cast<std::size_t>(frame_count) * kChannelCount * sizeof(float));
            release_flags = AUDCLNT_BUFFERFLAGS_SILENT;
            StoreError(L"Unknown exception in audio render callback");
        }

        ThrowIfFailed(render_client->ReleaseBuffer(frame_count, release_flags),
                      "IAudioRenderClient::ReleaseBuffer");
    }

    void StoreError(std::wstring error) noexcept {
        try {
            std::scoped_lock lock(error_mutex_);
            error_ = std::move(error);
        } catch (...) {
            // Audio recovery must continue even if reporting runs out of memory.
        }
    }

    UniqueHandle stop_event_;
    std::thread thread_;
    RenderCallback callback_;
    std::mutex error_mutex_;
    std::wstring error_;
};

WasapiOutput::WasapiOutput() : implementation_(std::make_unique<Impl>()) {}

WasapiOutput::~WasapiOutput() = default;

void WasapiOutput::Start(RenderCallback callback) {
    implementation_->Start(std::move(callback));
}

void WasapiOutput::Stop() noexcept {
    implementation_->Stop();
}

std::wstring WasapiOutput::ConsumeError() {
    return implementation_->ConsumeError();
}

} // namespace procedural_audio
