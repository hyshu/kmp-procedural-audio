cmake_minimum_required(VERSION 3.20)
project(pcm_audio LANGUAGES CXX)

include(CTest)
find_package(Threads REQUIRED)

option(PCM_AUDIO_BUILD_BACKEND "Build the platform audio backend" ON)
option(PCM_AUDIO_BUNDLE_RUNTIME "Include the GNU C++ runtime in the static archive" OFF)

function(pcm_audio_configure target)
    target_compile_features(${target} PUBLIC cxx_std_17)
    target_include_directories(${target} PUBLIC include PRIVATE src)
    target_link_libraries(${target} PUBLIC Threads::Threads)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4 /permissive-)
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic)
    endif()
endfunction()

if(PCM_AUDIO_BUILD_BACKEND)
    add_library(pcm_audio STATIC src/pcm_audio.cpp src/platform_backend.cpp)
    pcm_audio_configure(pcm_audio)
    set_target_properties(pcm_audio PROPERTIES POSITION_INDEPENDENT_CODE ON)
    if(WIN32)
        target_sources(pcm_audio PRIVATE src/wasapi_output.cpp)
        target_compile_definitions(pcm_audio PRIVATE NOMINMAX=1 WIN32_LEAN_AND_MEAN=1)
        target_link_libraries(pcm_audio PUBLIC avrt ole32 uuid)
    elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
        target_sources(pcm_audio PRIVATE src/alsa_audio_output.cpp)
        target_link_libraries(pcm_audio PUBLIC ${CMAKE_DL_LIBS})
    else()
        message(FATAL_ERROR "The C backend supports Windows and Linux. Set PCM_AUDIO_BUILD_BACKEND=OFF to run lifecycle tests on another host.")
    endif()
    if(PCM_AUDIO_BUNDLE_RUNTIME)
        if(MSVC)
            message(FATAL_ERROR "Use a MinGW compiler for Kotlin Native output archives")
        endif()
        set(PCM_AUDIO_COMPILER_QUERY)
        if(CMAKE_CXX_COMPILER_TARGET)
            list(APPEND PCM_AUDIO_COMPILER_QUERY "--target=${CMAKE_CXX_COMPILER_TARGET}")
        endif()
        if(CMAKE_SYSROOT)
            list(APPEND PCM_AUDIO_COMPILER_QUERY "--sysroot=${CMAKE_SYSROOT}")
        endif()
        if(PCM_AUDIO_LINUX_ROOT AND CMAKE_SYSTEM_NAME STREQUAL "Linux")
            list(APPEND PCM_AUDIO_COMPILER_QUERY "--gcc-toolchain=${PCM_AUDIO_LINUX_ROOT}")
        endif()
        foreach(runtime stdc++ gcc_eh)
            execute_process(COMMAND "${CMAKE_CXX_COMPILER}" ${PCM_AUDIO_COMPILER_QUERY}
                "-print-file-name=lib${runtime}.a"
                OUTPUT_VARIABLE runtime_path OUTPUT_STRIP_TRAILING_WHITESPACE)
            if(NOT EXISTS "${runtime_path}")
                message(FATAL_ERROR "The compiler cannot locate lib${runtime}.a")
            endif()
            list(APPEND PCM_AUDIO_RUNTIME_LIBRARIES "${runtime_path}")
        endforeach()
        set_target_properties(pcm_audio PROPERTIES OUTPUT_NAME pcm_audio_objects)
        add_custom_command(TARGET pcm_audio POST_BUILD
            COMMAND "${CMAKE_COMMAND}"
                "-DPCM_AUDIO_AR=${CMAKE_AR}"
                "-DPCM_AUDIO_INPUT=$<TARGET_FILE:pcm_audio>"
                "-DPCM_AUDIO_OUTPUT=${CMAKE_CURRENT_BINARY_DIR}/libpcm_audio.a"
                "-DPCM_AUDIO_RUNTIMES=${PCM_AUDIO_RUNTIME_LIBRARIES}"
                -P "${CMAKE_CURRENT_SOURCE_DIR}/cmake/bundle-runtime.cmake"
            VERBATIM)
    endif()
endif()

if(BUILD_TESTING)
    add_executable(pcm_audio_lifecycle_test src/pcm_audio.cpp tests/lifecycle_test.cpp)
    pcm_audio_configure(pcm_audio_lifecycle_test)
    add_test(NAME pcm_audio_lifecycle COMMAND pcm_audio_lifecycle_test)
    set_tests_properties(pcm_audio_lifecycle PROPERTIES TIMEOUT 30)
    if(PCM_AUDIO_BUILD_BACKEND)
        add_executable(pcm_audio_backend_test tests/backend_test.cpp)
        pcm_audio_configure(pcm_audio_backend_test)
        target_link_libraries(pcm_audio_backend_test PRIVATE pcm_audio)
        add_test(NAME pcm_audio_backend COMMAND pcm_audio_backend_test)
        set_tests_properties(pcm_audio_backend PROPERTIES TIMEOUT 30)
        if(WIN32)
            # Hosted Windows runners may have no audio endpoint.
            set_tests_properties(pcm_audio_backend PROPERTIES SKIP_RETURN_CODE 77)
        elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            # Use the real ALSA library with its null device so CI needs no sound card.
            file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/null-alsa.conf" "pcm.!default { type null }\n")
            set_tests_properties(pcm_audio_backend PROPERTIES ENVIRONMENT
                "ALSA_CONFIG_PATH=${CMAKE_CURRENT_BINARY_DIR}/null-alsa.conf")
        endif()
    endif()
    if(UNIX)
        add_library(pcm_audio_fake_alsa SHARED tests/fake_alsa.cpp)
        pcm_audio_configure(pcm_audio_fake_alsa)
        set_target_properties(pcm_audio_fake_alsa PROPERTIES OUTPUT_NAME asound SUFFIX ".so.2")
        add_executable(pcm_audio_alsa_test src/pcm_audio.cpp src/platform_backend.cpp
            src/alsa_audio_output.cpp tests/alsa_test.cpp)
        pcm_audio_configure(pcm_audio_alsa_test)
        target_link_libraries(pcm_audio_alsa_test PRIVATE pcm_audio_fake_alsa ${CMAKE_DL_LIBS})
        add_test(NAME pcm_audio_alsa COMMAND pcm_audio_alsa_test)
        set_tests_properties(pcm_audio_alsa PROPERTIES ENVIRONMENT
            "LD_LIBRARY_PATH=$<TARGET_FILE_DIR:pcm_audio_fake_alsa>;DYLD_LIBRARY_PATH=$<TARGET_FILE_DIR:pcm_audio_fake_alsa>"
            TIMEOUT 30)
    endif()
endif()
