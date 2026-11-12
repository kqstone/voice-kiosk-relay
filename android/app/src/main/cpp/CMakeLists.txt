# Voice Kiosk on-device LLM (S0): JNI bridge over a pinned llama.cpp.
#
# llama.cpp sources are fetched (pinned + verified) by scripts/fetch-llama-cpp.sh
# into third_party/llama.cpp; they are not committed.
#
# CPU portability: the base libraries are compiled for plain armv8-a
# (GGML_NATIVE=OFF, no global -march). Optimised CPU kernels are built as
# separate backend variants (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL):
# libggml-cpu-android_armv8.0_1.so (baseline), ..._armv8.2_1 (dotprod),
# ..._armv8.2_2 (+fp16), ..._armv8.6_1 (+i8mm), ..._armv9.* (sve2/sme).
# At runtime ggml scores each variant against the CPU's HWCAPs and only loads
# the best one the device actually supports, so older cores never execute
# dotprod/i8mm instructions.
cmake_minimum_required(VERSION 3.22.1)
project(vk_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_SRC}. "
        "Run: bash scripts/fetch-llama-cpp.sh (also invoked by scripts/download-models.sh)")
endif()

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "vk_llm only supports arm64-v8a (got ${ANDROID_ABI})")
endif()

# ---- llama.cpp / ggml configuration (cache vars so subproject options pick them up)
set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(GGML_SYSTEM_ARCH         "ARM" CACHE STRING "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI        OFF CACHE BOOL "" FORCE)  # would FetchContent at build time
set(GGML_VULKAN              OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL              OFF CACHE BOOL "" FORCE)
set(GGML_RPC                 OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER       OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP          OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD         OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL            OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)

# Reproducible build info: llama.cpp / ggml stamp `git rev-parse HEAD` (and a -dirty flag) of
# whatever repository contains their sources. The pinned tarball has no .git, so without this
# they would embed this app's commit and dirty state and every commit would change the .so
# files. Stop git discovery at third_party/ and stamp the pinned llama.cpp version instead
# (keep in sync with scripts/fetch-llama-cpp.sh).
# Source, build and NDK paths (__FILE__ in ggml asserts, DWARF comp dirs that feed the build id)
# are made relative so the libraries do not depend on where the repository is checked out or
# where the NDK is installed (local box vs CI runner).
add_compile_options(
    "-ffile-prefix-map=${CMAKE_CURRENT_LIST_DIR}/="
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=build"
    "-ffile-prefix-map=${ANDROID_NDK}=ndk")
set(ENV{GIT_CEILING_DIRECTORIES} "${CMAKE_CURRENT_LIST_DIR}/third_party")
set(LLAMA_BUILD_NUMBER 0)
set(LLAMA_BUILD_COMMIT "v0.9.0-cd959ba")

add_subdirectory(${LLAMA_SRC} build-llama EXCLUDE_FROM_ALL)

add_library(vk_llm SHARED
    vk_llm_core.cpp
    vk_llm_jni.cpp)

target_include_directories(vk_llm PRIVATE ${CMAKE_CURRENT_LIST_DIR})
target_link_libraries(vk_llm PRIVATE llama ggml ggml-base android log)

# The CPU variants are loaded with dlopen, so nothing links against them;
# make the app target depend on them so they are built and packaged.
foreach(variant
        android_armv8.0_1 android_armv8.2_1 android_armv8.2_2 android_armv8.6_1
        android_armv9.0_1 android_armv9.2_1 android_armv9.2_2)
    if(TARGET ggml-cpu-${variant})
        add_dependencies(vk_llm ggml-cpu-${variant})
    endif()
endforeach()

# Smaller binaries: strip unused sections, keep only exported JNI symbols.
target_compile_options(vk_llm PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(vk_llm PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
