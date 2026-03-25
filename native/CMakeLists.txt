cmake_minimum_required(VERSION 3.22)
project(liblocalmotion_backend.so C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(LOCALMOTION_ENABLE_QNN "Enable QNN integration hooks" OFF)

if(DEFINED ENV{QNN_SDK_ROOT})
    set(LOCALMOTION_ENABLE_QNN ON)
    add_compile_definitions(LOCALMOTION_QNN_AVAILABLE=1)
endif()

add_compile_options(
    -O2
    -Wall
    -Wextra
    -fPIC
)

add_executable(${PROJECT_NAME}
    src/main.cpp
)

target_link_libraries(${PROJECT_NAME}
    PRIVATE
    log
)

