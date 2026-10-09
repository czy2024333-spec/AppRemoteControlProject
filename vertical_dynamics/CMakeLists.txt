cmake_minimum_required(VERSION 3.16)
project(vertical_dynamics LANGUAGES CXX)

add_library(vertical_dynamics src/VerticalDynamics.cpp)
target_include_directories(vertical_dynamics PUBLIC include)
target_compile_features(vertical_dynamics PUBLIC cxx_std_17)

include(CTest)
if(BUILD_TESTING)
    add_executable(vertical_dynamics_tests tests/VerticalDynamicsTests.cpp)
    target_link_libraries(vertical_dynamics_tests PRIVATE vertical_dynamics)
    add_test(NAME vertical_dynamics_tests COMMAND vertical_dynamics_tests)
endif()
