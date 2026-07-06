package com.example.ftpget

object RtkEngine {

    // 🌟 核心修复：加载正确的库名！
    init {
        System.loadLibrary("rtklib_engine")
    }

    // 接收我们刚才在 MainActivity 里传过来的终极参数数组
    external fun runPpp(args: Array<String>): Int

    // 如果你之前还有 startProcessing，保留或者删掉都可以，我们现在用 runPpp
}