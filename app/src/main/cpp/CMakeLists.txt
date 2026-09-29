cmake_minimum_required(VERSION 3.22.1)
project("cameragun")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenCV Android SDK Configuration
# Set OPENCV_ANDROID_SDK path via gradle or fallback to relative directory
if(NOT DEFINED OpenCV_DIR)
    set(OpenCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../OpenCV-android-sdk/sdk/native/jni)
endif()

find_package(OpenCV REQUIRED)

include_directories(
    ${OpenCV_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

add_library(cameragun_vision SHARED
    vision_pipeline.cpp
)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(cameragun_vision
    ${OpenCV_LIBS}
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
