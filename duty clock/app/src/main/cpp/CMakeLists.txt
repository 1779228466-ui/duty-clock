cmake_minimum_required(VERSION 3.22.1)
project(dutyclock_pose)

set(NCNN_VULKAN OFF CACHE BOOL "Enable Vulkan GPU compute")

if(NCNN_VULKAN)
    set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260526-android-vulkan/${ANDROID_ABI}/lib/cmake/ncnn)
else()
    set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260526-android/${ANDROID_ABI}/lib/cmake/ncnn)
endif()

find_package(ncnn REQUIRED)

add_library(dutyclock_ncnn SHARED dutyclock_ncnn_jni.cpp)

if(NCNN_VULKAN)
    target_include_directories(dutyclock_ncnn PRIVATE ${CMAKE_SOURCE_DIR}/ncnn-20260526-android-vulkan/${ANDROID_ABI}/include/ncnn)
else()
    target_include_directories(dutyclock_ncnn PRIVATE ${CMAKE_SOURCE_DIR}/ncnn-20260526-android/${ANDROID_ABI}/include/ncnn)
endif()

target_compile_definitions(dutyclock_ncnn PRIVATE NCNN_VULKAN=0)

target_compile_options(dutyclock_ncnn PRIVATE
    -O3
    -march=armv8.4-a+fp16+dotprod
    -mtune=cortex-a720
    -flto=thin
    -ffast-math
    -fno-exceptions
    -fno-rtti
    -fomit-frame-pointer
    -DNDEBUG
)

target_link_options(dutyclock_ncnn PRIVATE
    -flto=thin
)

if(NCNN_VULKAN)
    target_link_libraries(dutyclock_ncnn ncnn android log vulkan)
else()
    target_link_libraries(dutyclock_ncnn ncnn android log)
endif()