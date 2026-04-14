cmake_minimum_required(VERSION 3.22)
project(liblocalmotion_backend.so C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LOCALMOTION_ENABLE_QNN "Enable QNN integration hooks" OFF)

if(DEFINED ENV{QNN_SDK_ROOT})
    set(LOCALMOTION_ENABLE_QNN ON)
    add_compile_definitions(LOCALMOTION_QNN_AVAILABLE=1)
endif()

# Third-party paths (all relative to native/ directory)
set(NATIVE_ROOT ${CMAKE_CURRENT_SOURCE_DIR})
set(THIRD_PARTY ${NATIVE_ROOT}/third_party)

# QNN SDK: prefer CMAKE_QNN_SDK_ROOT > env QNN_SDK_ROOT > bundled SDK
if(DEFINED CMAKE_QNN_SDK_ROOT)
    set(QNN_SDK ${CMAKE_QNN_SDK_ROOT})
elseif(DEFINED ENV{QNN_SDK_ROOT})
    set(QNN_SDK $ENV{QNN_SDK_ROOT})
else()
    # Try bundled SDK
    set(QNN_SDK ${THIRD_PARTY}/qnn/v2.44.0.260225)
endif()

if(EXISTS ${QNN_SDK}/include/QNN/QnnBackend.h)
    set(LOCALMOTION_ENABLE_QNN ON)
    add_compile_definitions(LOCALMOTION_QNN_AVAILABLE=1)
    message(STATUS "QNN SDK found: ${QNN_SDK}")
else()
    message(STATUS "QNN SDK not found, building without QNN support")
endif()

# Include directories
set(PACKAGE_INCLUDES
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${THIRD_PARTY}/xtensor/include
    ${THIRD_PARTY}/xtl/include
    ${THIRD_PARTY}/xsimd/include
    ${THIRD_PARTY}/stb
    ${THIRD_PARTY}/json/include
    ${THIRD_PARTY}/cpp-httplib
)

if(LOCALMOTION_ENABLE_QNN)
    # QNN SampleApp source
    set(SAMPLEAPP_SRC ${THIRD_PARTY}/SampleApp/src)

    list(APPEND PACKAGE_INCLUDES
        ${QNN_SDK}/include/QNN
        ${SAMPLEAPP_SRC}
        ${SAMPLEAPP_SRC}/Log
        ${SAMPLEAPP_SRC}/PAL/include
        ${SAMPLEAPP_SRC}/Utils
        ${SAMPLEAPP_SRC}/WrapperUtils
    )

    # SampleApp source files
    file(GLOB SAMPLEAPP_SOURCES
        "${SAMPLEAPP_SRC}/QnnSampleApp.cpp"
        "${SAMPLEAPP_SRC}/Log/*.cpp"
        "${SAMPLEAPP_SRC}/PAL/src/linux/*.cpp"
        "${SAMPLEAPP_SRC}/PAL/src/common/*.cpp"
        "${SAMPLEAPP_SRC}/Utils/*.cpp"
        "${SAMPLEAPP_SRC}/WrapperUtils/*.cpp"
    )

    list(APPEND PACKAGE_INCLUDES
        ${SAMPLEAPP_SRC}/Utils
    )
endif()

add_compile_options(
    -O2
    -Wall
    -fPIC
)

# Source files
set(SOURCES src/main.cpp)

if(LOCALMOTION_ENABLE_QNN)
    list(APPEND SOURCES ${SAMPLEAPP_SOURCES})
    add_definitions(-DQNN_API=__attribute__\(\(visibility\(\"default\"\)\)\))
endif()

add_executable(${PROJECT_NAME} ${SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE ${PACKAGE_INCLUDES})

target_link_libraries(${PROJECT_NAME} PRIVATE log dl)

if(LOCALMOTION_ENABLE_QNN AND DEFINED ENV{QNN_TOKENIZERS_BUILT})
    # Link tokenizers-cpp if available
    # target_link_libraries(${PROJECT_NAME} PRIVATE tokenizers_cpp)
endif()
