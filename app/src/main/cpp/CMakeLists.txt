cmake_minimum_required(VERSION 3.10.2)
project("rtklib_engine")

# Match the PC observation/satellite layout in C and C++ translation units.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsigned-char")
# Both entry points compile the same unmodified snapshot of the PC core.
find_library(log-lib log)

# Independent real-time PPP prototype. The vendored sources are an unchanged
# snapshot of the PC-validated core; do not mix them with the older engine above.
set(PPP_LIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/ppp_live/rtklib")
include("${CMAKE_CURRENT_SOURCE_DIR}/verify_ppp_profile.cmake")
set(PPP_LIVE_SOURCES
        "${PPP_LIVE_DIR}/smartphone_ppp_config.c"
        "${PPP_LIVE_DIR}/gnss_adapter.c"
        "${PPP_LIVE_DIR}/gnss_signal_policy.c"
        "${PPP_LIVE_DIR}/rtkcmn.c"
        "${PPP_LIVE_DIR}/rtkpos.c"
        "${PPP_LIVE_DIR}/ppp.c"
        "${PPP_LIVE_DIR}/ppp_ar.c"
        "${PPP_LIVE_DIR}/pntpos.c"
        "${PPP_LIVE_DIR}/preceph.c"
        "${PPP_LIVE_DIR}/rinex.c"
        "${PPP_LIVE_DIR}/ionex.c"
        "${PPP_LIVE_DIR}/tides.c"
        "${PPP_LIVE_DIR}/sofa.c"
        "${PPP_LIVE_DIR}/lambda.c"
        "${PPP_LIVE_DIR}/sbas.c"
        "${PPP_LIVE_DIR}/ephemeris.c"
        "${PPP_LIVE_DIR}/geoid.c"
        "${PPP_LIVE_DIR}/solution.c"
        "${PPP_LIVE_DIR}/trace.c")
add_library(gnss_ppp_live SHARED gnss_jni.cpp ${PPP_LIVE_SOURCES})
target_include_directories(gnss_ppp_live PRIVATE "${PPP_LIVE_DIR}")
target_compile_definitions(gnss_ppp_live PRIVATE TRACE ENAGLO NFREQ=4)
target_compile_options(gnss_ppp_live PRIVATE -fsigned-char -fvisibility=hidden -Wno-macro-redefined)
target_link_options(gnss_ppp_live PRIVATE -Wl,-Bsymbolic)
target_link_libraries(gnss_ppp_live ${log-lib} m)

# RINEX postprocessing uses the PC postpos.c and the same PPP core. The JNI
# bridge only replaces the PC console's hard-coded Windows paths and getchar().
add_library(rtklib_engine SHARED rtk_jni.c ${PPP_LIVE_SOURCES}
        "${PPP_LIVE_DIR}/postpos.c"
        "${PPP_LIVE_DIR}/rtcm.c"
        "${PPP_LIVE_DIR}/rtcm2.c"
        "${PPP_LIVE_DIR}/rtcm3.c"
        "${PPP_LIVE_DIR}/rtcm3e.c")
target_include_directories(rtklib_engine PRIVATE "${PPP_LIVE_DIR}")
target_compile_definitions(rtklib_engine PRIVATE TRACE ENAGLO NFREQ=4)
target_compile_options(rtklib_engine PRIVATE -fsigned-char -fvisibility=hidden)
target_link_options(rtklib_engine PRIVATE -Wl,-Bsymbolic)
target_link_libraries(rtklib_engine ${log-lib} m)
