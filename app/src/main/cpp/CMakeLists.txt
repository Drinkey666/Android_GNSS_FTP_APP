cmake_minimum_required(VERSION 3.10.2)
project("rtklib_engine")

# 🌟 终极满血配置：防乱码 + 黑匣子 + 开启所有卫星系统 + 开启三频支持
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsigned-char -DTRACE -DENAGLO -DENAGAL -DENACMP -DENAQZS -DNFREQ=3")
# 包含头文件目录
include_directories("rtklib")

# 🌟 终极白名单（已补齐 RTCM 依赖）
set(RTKLIB_SRC
        "rtklib/rtkcmn.c"
        "rtklib/rinex.c"
        "rtklib/rtkpos.c"
        "rtklib/postpos.c"
        "rtklib/solution.c"
        "rtklib/lambda.c"
        "rtklib/geoid.c"
        "rtklib/sbas.c"
        "rtklib/preceph.c"
        "rtklib/pntpos.c"
        "rtklib/ephemeris.c"
        "rtklib/options.c"
        "rtklib/ppp.c"
        "rtklib/ppp_ar.c"
        "rtklib/ionex.c"
        "rtklib/tides.c"
        "rtklib/trace.c"
        "rtklib/rnx2rtkp.c"
        # 👇 这四个是刚才缺失的 RTCM 依赖
        "rtklib/rtcm.c"
        "rtklib/rtcm2.c"
        "rtklib/rtcm3.c"
        "rtklib/rtcm3e.c"
)

# 编译成安卓可以用的 .so 动态库
add_library(rtklib_engine SHARED
        rtk_jni.c
        ${RTKLIB_SRC})

# 链接安卓系统的底层日志库和数学库
find_library(log-lib log)
target_link_libraries(rtklib_engine ${log-lib} m)