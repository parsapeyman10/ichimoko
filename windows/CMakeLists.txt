cmake_minimum_required(VERSION 3.28)
project(AurumEdgeWindows VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_AUTOMOC ON)
include(GNUInstallDirs)
option(AURUM_BUILD_TESTS "Build deterministic core tests" ON)
option(AURUM_NATIVE_TUNE "Tune for the build machine; not for distributable builds" OFF)

add_library(aurum_core STATIC
    src/core/CsvReader.cpp
    src/core/Series.cpp
    src/core/Ichimoku.cpp
    src/core/BacktestEngine.cpp
)
target_include_directories(aurum_core PUBLIC src)
target_compile_features(aurum_core PUBLIC cxx_std_23)
set_target_properties(aurum_core PROPERTIES CXX_EXTENSIONS OFF)

if(MSVC)
    target_compile_options(aurum_core PRIVATE /W4 /permissive- /EHsc /Zc:__cplusplus)
    target_compile_options(aurum_core PRIVATE $<$<CONFIG:Release>:/O2 /GL /Gw>)
    target_link_options(aurum_core PRIVATE $<$<CONFIG:Release>:/LTCG>)
    if(AURUM_NATIVE_TUNE)
        target_compile_options(aurum_core PRIVATE /arch:AVX2)
    endif()
else()
    target_compile_options(aurum_core PRIVATE -Wall -Wextra -Wpedantic -Wconversion -Wshadow)
    if(AURUM_NATIVE_TUNE)
        target_compile_options(aurum_core PRIVATE -march=native)
    endif()
endif()

find_package(Qt6 6.8 REQUIRED COMPONENTS Quick QuickControls2 Sql Concurrent)
qt_standard_project_setup(REQUIRES 6.8)
qt_add_executable(aurum_edge WIN32
    src/app/main.cpp
    src/app/AppController.cpp
    src/app/CandleChart.cpp
    src/app/JournalStore.cpp
)
target_link_libraries(aurum_edge PRIVATE
    aurum_core
    Qt6::Quick
    Qt6::QuickControls2
    Qt6::Sql
    Qt6::Concurrent
)
set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
qt_add_qml_module(aurum_edge
    URI AurumEdge
    VERSION 1.0
    SOURCES
        src/app/CandleChart.h
    QML_FILES
        qml/Main.qml
        qml/HomePage.qml
        qml/ChartPage.qml
        qml/BacktestPage.qml
        qml/JournalPage.qml
        qml/SettingsPage.qml
        qml/Theme.qml
)

if(AURUM_BUILD_TESTS)
    enable_testing()
    add_executable(aurum_core_tests tests/CoreTests.cpp)
    target_link_libraries(aurum_core_tests PRIVATE aurum_core)
    add_test(NAME aurum_core_tests COMMAND aurum_core_tests)
endif()

install(TARGETS aurum_edge RUNTIME DESTINATION .)
if(WIN32)
    find_program(WINDEPLOYQT_EXECUTABLE windeployqt HINTS "${QT_DIR}/bin" "${Qt6_DIR}/../../../bin")
    if(WINDEPLOYQT_EXECUTABLE)
        install(CODE "execute_process(COMMAND \"${WINDEPLOYQT_EXECUTABLE}\" --qmldir \"${CMAKE_CURRENT_SOURCE_DIR}/qml\" \"\${CMAKE_INSTALL_PREFIX}/aurum_edge.exe\")")
    endif()
    set(CPACK_GENERATOR "NSIS")
    set(CPACK_PACKAGE_NAME "Aurum Edge")
    set(CPACK_PACKAGE_VENDOR "Aurum")
    set(CPACK_PACKAGE_INSTALL_DIRECTORY "Aurum Edge")
    include(CPack)
endif()
