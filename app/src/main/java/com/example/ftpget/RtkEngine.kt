package com.example.ftpget

object RtkEngine {
    // RINEX postprocessing compiles the same PC RTKLIB snapshot as live PPP,
    // plus the PC postpos.c. It remains a separate native library/state.
    init { System.loadLibrary("rtklib_engine") }

    /** OBS,NAV,SP3,CLK,BIA,IONEX,VMF3-left,VMF3-right,orography,ATX,POS,TRACE. */
    external fun runPpp(args: Array<String>): Int
}
