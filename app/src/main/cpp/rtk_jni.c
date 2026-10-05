#include <jni.h>
#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include "rtklib.h"
#include "smartphone_ppp_config.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PPP_POST", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PPP_POST", __VA_ARGS__)
#define NPATH 12

/* postpos.c expects these three CLI progress hooks. */
extern int showmsg(const char *format, ...)
{
    char message[512];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    LOGI("%s", message);
    return 0;
}
extern void settspan(gtime_t ts, gtime_t te) { (void)ts; (void)te; }
extern void settime(gtime_t time) { (void)time; }

static int nonempty_file(const char *path)
{
    struct stat st;
    return path && stat(path, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0;
}

static int set_path(char *target, size_t capacity, const char *path)
{
    if (!path || strlen(path) >= capacity) return 0;
    strcpy(target, path);
    return 1;
}

/* args: OBS, NAV, SP3, CLK, BIA, IONEX, VMF3-left, VMF3-right,
 *       orography, ATX, output.pos, output.pos.trace */
JNIEXPORT jint JNICALL
Java_com_example_ftpget_RtkEngine_runPpp(JNIEnv *env, jobject thiz,
                                          jobjectArray input)
{
    char *paths[NPATH] = {0};
    const char *infile[5];
    prcopt_t opt;
    solopt_t sol;
    filopt_t filopt = {0};
    gtime_t zero = {0};
    int i, result = -1, trace_open = 0;
    (void)thiz;
    if (!input || (*env)->GetArrayLength(env, input) != NPATH) {
        LOGE("Expected %d product/output paths", NPATH);
        return -1;
    }
    for (i = 0; i < NPATH; ++i) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, input, i);
        const char *utf;
        if (!item) goto done;
        utf = (*env)->GetStringUTFChars(env, item, NULL);
        if (!utf) { (*env)->DeleteLocalRef(env, item); goto done; }
        paths[i] = strdup(utf);
        (*env)->ReleaseStringUTFChars(env, item, utf);
        (*env)->DeleteLocalRef(env, item);
        if (!paths[i]) goto done;
        if (i < 10 && !nonempty_file(paths[i])) {
            LOGE("Missing or empty input: %s", paths[i]);
            result = -2;
            goto done;
        }
    }
    smartphone_ppp_configure(&opt, &sol);
    if (!set_path(filopt.satantp, sizeof(filopt.satantp), paths[9]) ||
        !set_path(filopt.iono, sizeof(filopt.iono), paths[5]) ||
        !set_path(filopt.dcb, sizeof(filopt.dcb), paths[4])) {
        LOGE("A product path exceeds RTKLIB's path limit");
        result = -1;
        goto done;
    }
    traceopen(paths[11]);
    tracelevel(3);
    trace_open = 1;
    {
        char description[2048];
        smartphone_ppp_describe(&opt, description, sizeof(description));
        LOGI("%s", description);
        trace(2, "%s,entry=android-postprocess\n", description);
    }
    if (!pppvmf3load(paths[6], paths[7], paths[8])) {
        LOGE("VMF3 pair or orography could not be loaded");
        result = -3;
        goto done;
    }
    infile[0] = paths[0];
    infile[1] = paths[1];
    infile[2] = paths[2];
    infile[3] = paths[3];
    infile[4] = paths[5];
    LOGI("PC-matched postprocessing: obs=%s, vmf0=%s, vmf1=%s",
         paths[0], paths[6], paths[7]);
    result = postpos(zero, zero, 0.0, 0.0, &opt, &sol, &filopt,
                     infile, 5, paths[10], "", "") == 0 ? 0 : -4;
    LOGI("postpos result=%d, output=%s", result, paths[10]);
done:
    if (trace_open) traceclose();
    for (i = 0; i < NPATH; ++i) free(paths[i]);
    return result;
}
