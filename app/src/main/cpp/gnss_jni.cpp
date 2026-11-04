/*
 * Android 实时输入桥：Kotlin GnssClock/GnssMeasurement → android_clock_t/android_meas_t。
 * 本文件输入层不读 TXT、不生成 OBS；随后调用已验证的 gnss_adapter 作 P/L/D/信号转换。
 * 详细字段/单位/线程与输出契约见 docs/ANDROID_GNSS_DATA_GUIDE.md。
 * B_VERIFIED_v2_JOSEPH: same byte-for-byte PC core, one fixed Android preset.
 */
#include <jni.h>
#include <android/log.h>
#include <time.h>
#include <sys/stat.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "android_b_verified.h"

extern "C" {
#include "ppp_live/rtklib/gnss_adapter.h"
#include "ppp_live/rtklib/smartphone_ppp_config.h"
#include "ppp_live/rtklib/ppp_innovation_diag.h"
}

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "PPP_LIVE", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "PPP_LIVE", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "PPP_OBS", __VA_ARGS__)

namespace {

constexpr int kProductCount = 9; // 固定路径顺序：NAV/SP3/CLK/BIA/IONEX/VMF左期/右期/格网高程/ATX。
constexpr int kMaxRawPerEvent = 512; // 单事件 measurement 数上限，不是卫星数；超过时整个事件失败。

// 会话内时钟历史；用于所有信号共享的静默 FullBias 跳变归一化，不作单星码误差改正。
struct ClockHistory { gnss_clock_reference_t reference{}; };

struct LiveEngine {
    rtk_t *rtk = nullptr;
    nav_t *nav = nullptr;
    gnss_adapter_t adapter{};
    ClockHistory history{};
    gtime_t last_epoch{};
    gtime_t vmf_first{}, vmf_last{};
    bool ready = false;
    std::string error;
    std::string last_obs_csv;
};

// JNI 初始化/处理/释放共享同一原生实例；互斥保护生命周期，防止停止时释放正在使用的内存。
std::mutex g_lock;
LiveEngine g_engine;

double monotonic_ms() {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return now.tv_sec * 1000.0 + now.tv_nsec * 1E-6;
}

bool regular_nonempty(const std::string &path) {
    struct stat st{};
    return stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0;
}

void free_products(nav_t *nav) {
    if (!nav) return;
    free(nav->eph); free(nav->geph); free(nav->seph);
    free(nav->peph); free(nav->pclk); free(nav->osbs);
    for (int i = 0; i < nav->nt; ++i) {
        free(nav->tec[i].data); free(nav->tec[i].rms);
    }
    free(nav->tec); free(nav->erp.data);
}

void release_engine() {
    if (g_engine.rtk) {
        if (g_engine.rtk->x) rtkfree(g_engine.rtk);
        free(g_engine.rtk);
    }
    free_products(g_engine.nav);
    free(g_engine.nav);
    g_engine.rtk = nullptr;
    g_engine.nav = nullptr;
    g_engine.history = ClockHistory{};
    g_engine.last_epoch = gtime_t{};
    g_engine.vmf_first = g_engine.vmf_last = gtime_t{};
    g_engine.ready = false;
    g_engine.last_obs_csv.clear();
    ppp_innovation_log_close();
    traceclose();
}

bool load_products(const std::vector<std::string> &p, prcopt_t *opt) {
    obs_t unused_obs{};
    sta_t station{};
    pcvs_t pcvs{};
    nav_t *nav = g_engine.nav;
    if (!ionex_time_select(nav, "CORRECTED")) return false;
    nav->legacy_product_reference = 0; // same product reference as the PC preset
    if (!readrnx(p[0].c_str(), 1, "", &unused_obs, nav, &station)) {
        g_engine.error = "NAV 读取失败";
        freeobs(&unused_obs);
        return false;
    }
    freeobs(&unused_obs);
    uniqnav(nav);
    readsp3(p[1].c_str(), nav, 0);
    if (!readrnxc(p[2].c_str(), nav)) {
        g_engine.error = "CLK 读取失败";
        return false;
    }
    if (!readdcb(p[3].c_str(), nav, &station)) {
        g_engine.error = "BIA 读取失败";
        return false;
    }
    readtec(p[4].c_str(), nav, 1);
    if (!readpcv(p[8].c_str(), &pcvs)) {
        g_engine.error = "ATX 读取失败";
        return false;
    }
    for (int i = 0; i < MAXSAT; ++i) {
        if (!(satsys(i + 1, nullptr) & opt->navsys)) continue;
        pcv_t *pcv = searchpcv(i + 1, "",
            nav->peph && nav->ne ? nav->peph[0].time : gpst2time(0, 0), &pcvs);
        if (pcv) nav->pcvs[i] = *pcv;
    }
    free(pcvs.pcv);
    if (!pppvmf3inspect(p[5].c_str(), p[6].c_str(), p[7].c_str(),
                       &g_engine.vmf_first, &g_engine.vmf_last) ||
        !pppvmf3load(p[5].c_str(), p[6].c_str(), p[7].c_str())) {
        g_engine.error = "VMF3 或格网高程读取失败";
        return false;
    }
    LOGI("PRODUCTS,NAV=%d,SP3=%d,CLK=%d,BIA=%d,IONEX=%d",
         nav->n, nav->ne, nav->nc, nav->nosb, nav->nt);
    if (!(nav->n > 0 && nav->ne >= 11 && nav->nc >= 2 && nav->nosb > 0 && nav->nt > 0)) {
        g_engine.error = "产品内容为空或不完整";
        return false;
    }
    return true;
}

/*
 * Freeze first valid FullBias/Bias per hardware-clock segment (DOI:10.3390/s23020777).
 * Shared C helper; ADR events never re-anchor the receiver clock.
 */
void normalize_clock(android_clock_t *clock) {
    android_clock_t raw=*clock;
    gnss_clock_reference_apply(&g_engine.history.reference,&raw,clock);
}


/*
 * @env 当前 JNI 调用的线程环境，不可跨线程缓存；source 是该 event 的 GnssClock。
 * @clock 输出纯 C 时钟结构；false 表示 JNI 取字段失败，缺 FullBias 交给钟段基准处理。
 * JNI 签名 J=Java long(64位)、D=double、I=int、Z=boolean；时间大整数不能先转 Double。
 * 本入口只取转换必需字段；不读取手机系统时间，不从 TXT 辅助 utcTimeMillis 取历元。
 */
bool read_clock(JNIEnv *env, jobject source, android_clock_t *clock) {
    if (!source) return false;
    jclass cls = env->GetObjectClass(source);
    if (!cls) return false;
    jmethodID time = env->GetMethodID(cls, "getTimeNanos", "()J");
    jmethodID has_full = env->GetMethodID(cls, "hasFullBiasNanos", "()Z");
    jmethodID full = env->GetMethodID(cls, "getFullBiasNanos", "()J");
    jmethodID has_bias = env->GetMethodID(cls, "hasBiasNanos", "()Z");
    jmethodID bias = env->GetMethodID(cls, "getBiasNanos", "()D");
    jmethodID hcdc = env->GetMethodID(cls, "getHardwareClockDiscontinuityCount", "()I");
    if (!time || !has_full || !full || !has_bias || !bias || !hcdc || env->ExceptionCheck()) {
        env->DeleteLocalRef(cls);
        return false;
    }
    memset(clock, 0, sizeof(*clock));
    clock->time_nanos = env->CallLongMethod(source, time); // 硬件时间(ns)，不是 UTC。
    clock->has_full_bias = env->CallBooleanMethod(source, has_full);
    if (clock->has_full_bias) clock->full_bias_nanos = env->CallLongMethod(source, full); // GPST 大钟偏(ns)。
    if (env->CallBooleanMethod(source, has_bias))
        clock->bias_nanos = env->CallDoubleMethod(source, bias);
    jmethodID has_leap=env->GetMethodID(cls,"hasLeapSecond","()Z");
    jmethodID leap=env->GetMethodID(cls,"getLeapSecond","()I");
    if(has_leap && leap && env->CallBooleanMethod(source,has_leap)) {
        clock->has_leap_seconds=1;clock->leap_seconds=env->CallIntMethod(source,leap);
    }
    clock->hardware_clock_discontinuity_count = env->CallIntMethod(source, hcdc); // 改变时载波弧段不连续。
    env->DeleteLocalRef(cls);
    return !env->ExceptionCheck();
}

/*
 * 数组中一项是“卫星×信号”，读取后不在这里拆历元/算伪距，整集合交给 adapter 合并。
 * out 是本调用的 C++ vector：复制数值而非保存 Java 对象，避免引用过期或跨线程使用。
 * optional CarrierFrequency/CodeType 先检查 hasXXX；缺失仍保持零/空，让 adapter 判不支持。
 */
bool read_measurements(JNIEnv *env, jobjectArray array,
                       std::vector<android_meas_t> *out) {
    if (!array) return false;
    jsize count = env->GetArrayLength(array);
    if (count <= 0 || count > kMaxRawPerEvent) return false;
    out->reserve(count);
    jobject first = env->GetObjectArrayElement(array, 0);
    if (!first) return false;
    jclass cls = env->GetObjectClass(first);
    env->DeleteLocalRef(first);
    if (!cls) return false;
    struct Methods {
        jmethodID sys, svid, offset, state, svtime, svunc, cn0, rate, rateunc;
        jmethodID adrstate, adr, adrunc, hasfreq, freq, hascode, code;
    } m{};
    m.sys = env->GetMethodID(cls, "getConstellationType", "()I");
    m.svid = env->GetMethodID(cls, "getSvid", "()I");
    m.offset = env->GetMethodID(cls, "getTimeOffsetNanos", "()D");
    m.state = env->GetMethodID(cls, "getState", "()I");
    m.svtime = env->GetMethodID(cls, "getReceivedSvTimeNanos", "()J");
    m.svunc = env->GetMethodID(cls, "getReceivedSvTimeUncertaintyNanos", "()J");
    m.cn0 = env->GetMethodID(cls, "getCn0DbHz", "()D");
    m.rate = env->GetMethodID(cls, "getPseudorangeRateMetersPerSecond", "()D");
    m.rateunc = env->GetMethodID(cls, "getPseudorangeRateUncertaintyMetersPerSecond", "()D");
    m.adrstate = env->GetMethodID(cls, "getAccumulatedDeltaRangeState", "()I");
    m.adr = env->GetMethodID(cls, "getAccumulatedDeltaRangeMeters", "()D");
    m.adrunc = env->GetMethodID(cls, "getAccumulatedDeltaRangeUncertaintyMeters", "()D");
    m.hasfreq = env->GetMethodID(cls, "hasCarrierFrequencyHz", "()Z");
    m.freq = env->GetMethodID(cls, "getCarrierFrequencyHz", "()F"); // Android 此 API 返回 float，不能用 ()D。
    m.hascode = env->GetMethodID(cls, "hasCodeType", "()Z");
    m.code = env->GetMethodID(cls, "getCodeType", "()Ljava/lang/String;");
    if (!m.sys || !m.svid || !m.offset || !m.state || !m.svtime || !m.svunc ||
        !m.cn0 || !m.rate || !m.rateunc || !m.adrstate || !m.adr || !m.adrunc ||
        !m.hasfreq || !m.freq || !m.hascode || !m.code || env->ExceptionCheck()) {
        env->DeleteLocalRef(cls);
        return false;
    }
    for (jsize i = 0; i < count; ++i) {
        jobject source = env->GetObjectArrayElement(array, i);
        if (!source) { env->DeleteLocalRef(cls); return false; }
        android_meas_t value{}; // 全零初始化，字符串自动保留末尾 '\0'；不是将缺失观测伪造成有效零。
        value.constellation_type = env->CallIntMethod(source, m.sys); // Android 枚举，不是 RTKLIB SYS 位掩码。
        value.svid = env->CallIntMethod(source, m.svid); // 星座内卫星号；adapter 才映射 RTKLIB sat。
        value.time_offset_nanos = env->CallDoubleMethod(source, m.offset); // 信号相对公共 Clock 的偏移(ns)。
        value.state = (uint32_t)env->CallIntMethod(source, m.state); // 跟踪/码锁/TOW 位掩码。
        value.sv_time_status=1;
        value.received_sv_time_nanos = env->CallLongMethod(source, m.svtime); // 卫星发射时标(ns)，保留整数。
        // 发射时标 1σ(ns)，此不确定度不是绝对时间，转 double 不损失整周时标。
        value.received_sv_time_uncertainty_nanos =
            (double)env->CallLongMethod(source, m.svunc);
        value.cn0_dbhz = env->CallDoubleMethod(source, m.cn0); // C/N0(dB-Hz)，不是 SNR(dB)。
        value.pseudorange_rate_mps = env->CallDoubleMethod(source, m.rate); // PRR(m/s)，adapter 转为 D=-PRR/λ。
        value.pseudorange_rate_uncertainty_mps = env->CallDoubleMethod(source, m.rateunc); // PRR 1σ(m/s)。
        value.adr_state = (uint32_t)env->CallIntMethod(source, m.adrstate); // VALID/RESET/SLIP/HALF 位。
        value.adr_meters = env->CallDoubleMethod(source, m.adr); // ADR(m)，adapter 转 L=ADR/λ。
        value.adr_uncertainty_meters = env->CallDoubleMethod(source, m.adrunc); // ADR 1σ(m)，当前输入门限 1.0 m。
        if (env->CallBooleanMethod(source, m.hasfreq))
            value.carrier_frequency_hz = env->CallFloatMethod(source, m.freq);
        if (env->CallBooleanMethod(source, m.hascode)) {
            auto text = (jstring)env->CallObjectMethod(source, m.code);
            if (text) {
                const char *utf = env->GetStringUTFChars(text, nullptr);
                if (utf) {
                    // 复制 CodeType(如 Q/P/C)，保留尾零；与 constellation+Hz 联合识别信号。
                    strncpy(value.code_type, utf, sizeof(value.code_type) - 1);
                    env->ReleaseStringUTFChars(text, utf);
                }
                env->DeleteLocalRef(text);
            }
        }
        // JNI 局部引用及时释放，否则每历元多信号可能积累引用；vector 中只保留已复制的值。
        env->DeleteLocalRef(source);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(cls); return false; }
        out->push_back(value);
    }
    env->DeleteLocalRef(cls);
    return true;
}

bool initialize_engine(const std::vector<std::string> &p) {
    if (g_engine.ready || p.size() != kProductCount) {
        g_engine.error = "PPP session already initialized or invalid product count";
        return false;
    }
    g_engine.error.clear();
    ppp_innovation_log_open(nullptr); // reset observer provenance for this session
    g_engine.nav = (nav_t *)calloc(1, sizeof(nav_t));
    g_engine.rtk = (rtk_t *)calloc(1, sizeof(rtk_t));
    if (!g_engine.nav || !g_engine.rtk) {
        g_engine.error = "本机内存不足";
        release_engine();
        return JNI_FALSE;
    }
    prcopt_t opt{};
    char config_error[256]{};
    if (!android_b_verified_configure(&opt, nullptr, config_error, sizeof(config_error))) {
        g_engine.error = std::string("B_VERIFIED 配置失败: ") + config_error;
        release_engine();
        return JNI_FALSE;
    }
    {
        char description[2048];
        smartphone_ppp_describe(&opt, description, sizeof(description));
        LOGI("%s", description);
        trace(2, "%s,entry=android-live\n", description);
    }
    if (!load_products(p, &opt)) {
        LOGE("nativeInit failed: %s", g_engine.error.c_str());
        release_engine();
        return JNI_FALSE;
    }
    gnss_adapter_init(&g_engine.adapter);
    gnss_adapter_set_adr_unc_max(&g_engine.adapter, 1.0, 0);
    gnss_adapter_set_ppp_safe(&g_engine.adapter, 1);
    rtkinit(g_engine.rtk, &opt); // exactly once per successful nativeInit()
    if (!g_engine.rtk->x || !g_engine.rtk->P) {
        g_engine.error = "RTKLIB 滤波状态分配失败";
        release_engine();
        return JNI_FALSE;
    }
    g_engine.ready = true;
    LOGI("nativeInit ready, PPP_SAFE=1, RINEX_OBS=0");
    return JNI_TRUE;
}

bool process_raw_epoch(android_clock_t clock, const std::vector<android_meas_t> &raw,
                       double start, std::vector<jdouble> *out) {
    if (!g_engine.ready || !out) {
        g_engine.error = "PPP 尚未初始化";
        return false;
    }
    g_engine.last_obs_csv.clear();
    normalize_clock(&clock);
    gtime_t epoch{};
    if (!gnss_adapter_clock_time(&clock, &epoch)) {
        g_engine.error = "GNSS 时间不可用";
        return false;
    }
    nav_t *nav = g_engine.nav;
    if (timediff(epoch, nav->peph[0].time) < 0.0 ||
        timediff(epoch, nav->peph[nav->ne - 1].time) > 0.0 ||
        timediff(epoch, nav->pclk[0].time) < 0.0 ||
        timediff(epoch, nav->pclk[nav->nc - 1].time) > 0.0) {
        g_engine.error = "当前 GNSS 时间不在 SP3/CLK 覆盖范围内";
        LOGE("PRODUCT_TIME_ERROR");
        return false;
    }
    if (!ionex_in_range(epoch, nav)) {
        g_engine.error = "当前 GNSS 时间不在 IONEX 覆盖范围内";
        LOGE("IONEX_TIME_ERROR");
        return false;
    }
    if (timediff(epoch, g_engine.vmf_first) < 0.0 ||
        timediff(epoch, g_engine.vmf_last) > 0.0) {
        g_engine.error = "当前 GNSS 时间不在 VMF3 覆盖范围内";
        LOGE("VMF3_TIME_ERROR");
        return false;
    }
    bool bias_in_time = false;
    for (int i = 0; i < nav->nosb; ++i) {
        if (timediff(epoch, nav->osbs[i].start) >= 0.0 &&
            timediff(nav->osbs[i].end, epoch) > 0.0) {
            bias_in_time = true;
            break;
        }
    }
    if (!bias_in_time) {
        g_engine.error = "当前 GNSS 时间不在 BIA 覆盖范围内";
        LOGE("BIA_TIME_ERROR");
        return false;
    }
    if (g_engine.last_epoch.time && timediff(epoch, g_engine.last_epoch) <= 0.001) {
        g_engine.error = "重复或逆序 GNSS 历元";
        LOGE("EPOCH_ORDER_ERROR");
        return false;
    }
    obsd_t obs[MAXOBS]{};
    gnss_adapter_stats_t stats{};
    int nobs = gnss_adapter_convert(&g_engine.adapter, &clock, raw.data(),
                                   (int)raw.size(), obs, MAXOBS, &stats);
    if (nobs <= 0) {
        g_engine.error = "本历元没有可用 PPP 观测";
        return false;
    }
    int week = 0, phase = 0, code = 0;
    double tow = time2gpst(obs[0].time, &week);
    g_engine.last_obs_csv.clear();
    for (int i = 0; i < nobs; ++i) {
        char sat[8]{};
        satno2id(obs[i].sat, sat);
        for (int f = 0; f < NFREQ; ++f) {
            if (!obs[i].code[f]) continue;
            if (obs[i].P[f]) code++;
            if (obs[i].L[f]) phase++;
            char row[256];
            snprintf(row, sizeof(row),
                     "%d,%.9f,%s,%s,%d,%.4f,%.6f,%.4f,%.3f,%u,%u\n",
                     week, tow, sat, code2obs(obs[i].code[f]), f + 1,
                     obs[i].P[f], obs[i].L[f], obs[i].D[f],
                     obs[i].SNR[f] * SNR_UNIT, obs[i].LLI[f], obs[i].code[f]);
            g_engine.last_obs_csv += row;
            LOGD("PPP_OBS,%d,%.9f,%s,%s,%d,%.4f,%.6f,%.4f,%.3f,%u,%u",
                 week, tow, sat, code2obs(obs[i].code[f]), f + 1,
                 obs[i].P[f], obs[i].L[f], obs[i].D[f],
                 obs[i].SNR[f] * SNR_UNIT, obs[i].LLI[f], obs[i].code[f]);
        }
    }
    const int result = rtkpos(g_engine.rtk, obs, nobs, nav); // once per event
    g_engine.last_epoch = epoch;
    g_engine.error.clear();
    const sol_t &sol = g_engine.rtk->sol;
    double pos[3] = {0.0, 0.0, 0.0};
    if (sol.stat) ecef2pos(sol.rr, pos);
    const double elapsed = monotonic_ms() - start;
    LOGI("PPP_EPOCH,week=%d,tow=%.3f,raw=%zu,obs_sat=%d,used_sat=%d,phase=%d,code=%d,Q=%d,rtkpos=%d,processing_ms=%.3f",
         week, tow, raw.size(), nobs, sol.ns, phase, code, sol.stat, result, elapsed);
    const jdouble output[] = {
        (double)week, tow, (double)sol.stat, (double)sol.ns,
        pos[0] * R2D, pos[1] * R2D, pos[2],
        sol.rr[0], sol.rr[1], sol.rr[2],
        sqrt(std::max((double)sol.qr[0], 0.0)),
        sqrt(std::max((double)sol.qr[1], 0.0)),
        sqrt(std::max((double)sol.qr[2], 0.0)),
        elapsed, (double)raw.size(), (double)nobs,
        (double)phase, (double)code
    };
    out->assign(output, output + sizeof(output) / sizeof(output[0]));
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ftpget_LivePppNative_nativeInit(
    JNIEnv *env, jobject, jobjectArray paths, jstring trace_path) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_engine.ready) {
        g_engine.error = "PPP 已初始化；先停止再重新初始化";
        return JNI_FALSE;
    }
    if (!paths || env->GetArrayLength(paths) != kProductCount) {
        g_engine.error = "必须提供 9 个产品路径";
        return JNI_FALSE;
    }
    std::vector<std::string> p;
    p.reserve(kProductCount);
    for (int i = 0; i < kProductCount; ++i) {
        auto value = (jstring)env->GetObjectArrayElement(paths, i);
        if (!value) { g_engine.error = "产品路径为空"; return JNI_FALSE; }
        const char *utf = env->GetStringUTFChars(value, nullptr);
        if (!utf) { env->DeleteLocalRef(value); return JNI_FALSE; }
        p.emplace_back(utf);
        env->ReleaseStringUTFChars(value, utf);
        env->DeleteLocalRef(value);
        if (!regular_nonempty(p.back())) {
            g_engine.error = "产品文件缺失或为空: " + p.back();
            return JNI_FALSE;
        }
    }
    if (!trace_path) { g_engine.error = "缺少会话 trace 路径"; return JNI_FALSE; }
    const char *trace_utf = env->GetStringUTFChars(trace_path, nullptr);
    if (!trace_utf) return JNI_FALSE;
    const std::string trace_file(trace_utf);
    env->ReleaseStringUTFChars(trace_path, trace_utf);
    FILE *probe = fopen(trace_file.c_str(), "w");
    if (!probe) { g_engine.error = "无法创建会话 trace"; return JNI_FALSE; }
    fclose(probe);
    traceopen(trace_file.c_str());
    tracelevel(2);
    return initialize_engine(p) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ftpget_LivePppNative_nativeProfile(JNIEnv *env, jobject) {
    prcopt_t opt{}; char error[256]{}, description[2048]{};
    if (!android_b_verified_configure(&opt, nullptr, error, sizeof(error)))
        return env->NewStringUTF(error);
    smartphone_ppp_describe(&opt, description, sizeof(description));
    return env->NewStringUTF(description);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_ftpget_LivePppNative_nativeProcessEpoch(
    JNIEnv *env, jobject, jobject source_clock, jobjectArray source_meas) {
    std::lock_guard<std::mutex> guard(g_lock);
    if (!g_engine.ready) { g_engine.error = "PPP 尚未初始化"; return nullptr; }
    const double start = monotonic_ms();
    android_clock_t clock{};
    std::vector<android_meas_t> raw;
    if (!read_clock(env, source_clock, &clock) ||
        !read_measurements(env, source_meas, &raw)) {
        g_engine.error = "GnssClock/GnssMeasurement 字段读取失败";
        if (env->ExceptionCheck()) env->ExceptionClear();
        LOGE("%s", g_engine.error.c_str());
        return nullptr;
    }
    std::vector<jdouble> output;
    if (!process_raw_epoch(clock, raw, start, &output)) return nullptr;
    jdoubleArray answer = env->NewDoubleArray((jsize)output.size());
    if (answer) env->SetDoubleArrayRegion(answer, 0,
                                         (jsize)output.size(), output.data());
    return answer;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ftpget_LivePppNative_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(g_engine.error.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_ftpget_LivePppNative_nativeObsRows(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(g_engine.last_obs_csv.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_ftpget_LivePppNative_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_engine();
    g_engine.error.clear();
    LOGI("nativeRelease completed");
}
