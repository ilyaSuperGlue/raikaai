cmake_minimum_required(VERSION 3.22.1)

project("raikaai_pty")

# Minimal PTY bridge for the vendored Termux terminal-emulator (Apache 2.0).
# Plain C, no dependencies beyond bionic libc. Builds for every ABI in seconds.
add_library(raikaai_pty SHARED raikaai_pty.c)

find_library(log-lib log)
target_link_libraries(raikaai_pty ${log-lib})
