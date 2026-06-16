cmake_minimum_required(VERSION 3.22.1)

project("motion-engine")

# Define the shared library target and its C++ sources
add_library(
    motion-engine
    SHARED
    motion-engine.cpp
)

# Search for the Android logging library (used for __android_log_print)
find_library(
    log-lib
    log
)

# Link our native library with the logging library
target_link_libraries(
    motion-engine
    ${log-lib}
)
