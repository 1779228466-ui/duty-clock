cmake_minimum_required(VERSION 3.22.1)
project(cposemouse_ncnn)

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn-20260526-android/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(cposemouse_ncnn SHARED cposemouse_ncnn_jni.cpp ikcp.c)
target_include_directories(cposemouse_ncnn PRIVATE ${CMAKE_SOURCE_DIR}/ncnn-20260526-android/${ANDROID_ABI}/include/ncnn)

target_compile_options(cposemouse_ncnn PRIVATE
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

target_link_options(cposemouse_ncnn PRIVATE
    -flto=thin
)

target_link_libraries(cposemouse_ncnn ncnn android log)
