cmake_minimum_required(VERSION 3.22.1)
project(palcoleve)

add_library(palcoleve SHARED native-lib.cpp)

target_include_directories(palcoleve PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/TinySoundFont)

target_compile_features(palcoleve PRIVATE cxx_std_17)
target_compile_options(palcoleve PRIVATE -O3 -ffast-math -fvisibility=hidden)

target_link_libraries(palcoleve aaudio log android m)
