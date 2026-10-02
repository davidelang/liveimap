cmake_minimum_required(VERSION 3.22.1)
project(liveimap)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(ART "${REPO_ROOT}/third_party/libetpan/artifact/${ANDROID_ABI}")
set(ETPAN_INCLUDE "${REPO_ROOT}/third_party/libetpan/src/build-android/include")

if(NOT EXISTS "${ART}/libetpan.a")
    message(FATAL_ERROR "missing ${ART}/libetpan.a")
endif()

add_library(liveimap SHARED liveimap_jni.cpp)
target_include_directories(liveimap PRIVATE "${ETPAN_INCLUDE}")

find_library(z-lib z)
find_library(dl-lib dl)
find_library(log-lib log)
if(NOT z-lib OR NOT dl-lib OR NOT log-lib)
    message(FATAL_ERROR "libz, libdl, or liblog not found")
endif()

# Archive order is the plan contract: etpan, sasl2, ssl, crypto, fastjson, iconv, then z, dl, and log.
target_link_libraries(liveimap
    "${ART}/libetpan.a"
    "${ART}/libsasl2.a"
    "${ART}/libssl.a"
    "${ART}/libcrypto.a"
    "${ART}/libfastjson.a"
    "${ART}/libiconv.a"
    ${z-lib}
    ${dl-lib}
    ${log-lib}
)
