cmake_minimum_required(VERSION 3.22.1)
project("xmotion_native")

add_library(
        xmotion_native
        SHARED
        native-lib.cpp)

find_library(log-lib log)

target_link_libraries(
        xmotion_native
        ${log-lib})
