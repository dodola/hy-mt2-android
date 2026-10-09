cmake_minimum_required(VERSION 3.22)
project(hymt2_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Patched llama.cpp (branch hymt2, with STQ1_0) lives in third_party.
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(hymt2_jni SHARED hymt_jni.cpp hymt_engine.cpp)
target_include_directories(hymt2_jni PRIVATE
    ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(hymt2_jni PRIVATE llama android log)
