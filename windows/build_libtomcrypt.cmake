# CMake script to build LibTomCrypt as a shared library for Windows
# Usage: cmake -P build_libtomcrypt.cmake

cmake_minimum_required(VERSION 3.22)

# Set paths
set(LIBTOMCRYPT_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party/libtomcrypt")
set(LIBTOMMATH_SOURCE_DIR "${CMAKE_CURRENT_LIST_DIR}/../third_party/libtommath")
set(BUILD_DIR "${CMAKE_CURRENT_LIST_DIR}/build/libtomcrypt")

# Create build directory
file(MAKE_DIRECTORY ${BUILD_DIR})

# Configure and build
execute_process(
    COMMAND cmake
        -S ${LIBTOMCRYPT_SOURCE_DIR}
        -B ${BUILD_DIR}
        -DCMAKE_BUILD_TYPE=Release
        -DBUILD_SHARED_LIBS=ON
        -DWITH_LTM=ON
        -DLIBTOMMATH_SOURCE_DIR=${LIBTOMMATH_SOURCE_DIR}
        -DCMAKE_INSTALL_PREFIX=${CMAKE_CURRENT_LIST_DIR}/install
    WORKING_DIRECTORY ${CMAKE_CURRENT_LIST_DIR}
    RESULT_VARIABLE CONFIGURE_RESULT
)

if(NOT CONFIGURE_RESULT EQUAL 0)
    message(FATAL_ERROR "CMake configure failed: ${CONFIGURE_RESULT}")
endif()

# Build
execute_process(
    COMMAND cmake --build ${BUILD_DIR} --config Release --parallel
    WORKING_DIRECTORY ${BUILD_DIR}
    RESULT_VARIABLE BUILD_RESULT
)

if(NOT BUILD_RESULT EQUAL 0)
    message(FATAL_ERROR "Build failed: ${BUILD_RESULT}")
endif()

# Install
execute_process(
    COMMAND cmake --install ${BUILD_DIR} --config Release
    WORKING_DIRECTORY ${BUILD_DIR}
    RESULT_VARIABLE INSTALL_RESULT
)

if(NOT INSTALL_RESULT EQUAL 0)
    message(FATAL_ERROR "Install failed: ${INSTALL_RESULT}")
endif()

message(STATUS "LibTomCrypt built successfully!")
message(STATUS "Library installed to: ${CMAKE_CURRENT_LIST_DIR}/install")
