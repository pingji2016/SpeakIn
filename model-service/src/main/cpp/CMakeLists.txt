cmake_minimum_required(VERSION 3.22)
project("speakin_llama")

set(LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml.so)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml-base.so)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libggml-cpu.so)

add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libllama.so)

add_library(speakin_llama SHARED
    llama_bridge.cpp
)

target_include_directories(speakin_llama PRIVATE
    ${CMAKE_SOURCE_DIR}/include
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/include
)

target_link_libraries(speakin_llama
    llama
    ggml
    ggml-base
    ggml-cpu
    log
)
