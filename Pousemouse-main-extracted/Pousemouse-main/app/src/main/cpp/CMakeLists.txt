cmake_minimum_required(VERSION 3.22.1)
project(posemouse_ncnn)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260526-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(posemouse_ncnn SHARED posemouse_ncnn_jni.cpp ikcp.c)
target_include_directories(posemouse_ncnn PRIVATE ${CMAKE_SOURCE_DIR}/ncnn-20260526-android-vulkan/${ANDROID_ABI}/include/ncnn)
target_link_libraries(posemouse_ncnn ncnn android log)

add_library(neuron_direct SHARED neuron_direct_jni.cpp)
target_include_directories(neuron_direct PRIVATE ${CMAKE_SOURCE_DIR}/neuropilot_include)
target_link_libraries(neuron_direct android log dl)