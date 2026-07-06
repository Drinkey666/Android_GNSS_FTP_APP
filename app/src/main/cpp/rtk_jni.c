#include <jni.h>
#include <string.h>
#include <android/log.h>
#include "rtklib.h" // 引入 RTKLIB 头文件

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "RTKLIB_JNI", __VA_ARGS__)

// 声明 rnx2rtkp 的主函数 (你需要把 rnx2rtkp.c 里的 main 函数改名为 rnx2rtkp_main)
extern int rnx2rtkp_main(int argc, char **argv);

JNIEXPORT jint JNICALL
Java_com_example_ftpget_RtkEngine_runPpp(JNIEnv *env, jobject thiz, jobjectArray argsArray) {

    // 1. 将 Kotlin 的 String[] 转换为 C 的 char**
    int argc = (*env)->GetArrayLength(env, argsArray);
    char **argv = (char **)malloc(argc * sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring string = (jstring) (*env)->GetObjectArrayElement(env, argsArray, i);
        const char *rawString = (*env)->GetStringUTFChars(env, string, 0);
        argv[i] = strdup(rawString);
        (*env)->ReleaseStringUTFChars(env, string, rawString);
    }

    LOGI("开始调用 RTKLIB 事后解算引擎...");

    // 2. 调用核心解算函数
    int result = rnx2rtkp_main(argc, argv);

    LOGI("解算结束，返回码: %d", result);

    // 3. 释放内存
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);

    return result;
}