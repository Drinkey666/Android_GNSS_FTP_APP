/* Device-only verifier. Not part of the APK or a live data source.
 * Includes the actual JNI bridge so initialization/clock normalization/
 * product checks/adapter/rtkpos use exactly the production helper functions.
 */
#include "../gnss_jni.cpp"
#include <limits>

static int checks = 0, failures = 0;
#define CHECK(c) do { ++checks; if (!(c)) { ++failures; fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #c); } } while (0)

static void numerical_tests() {
    prcopt_t opt; solopt_t sol; char error[256], text[2048];
    CHECK(android_b_verified_configure(&opt, &sol, error, sizeof(error)));
    CHECK(opt.ppp_baseline == PPP_BASELINE_B_LEGACY);
    CHECK(opt.ppp_lifecycle == PPP_LIFECYCLE_VERIFIED);
    CHECK(opt.ppp_clock_mode == PPP_CLOCK_COMMON);
    CHECK(opt.navsys == (SYS_GPS | SYS_GAL | SYS_CMP) && opt.nf == 4);
    CHECK(!opt.ppp_iekf && !opt.ppp_physical_atmosphere && !opt.ppp_rcb_datum);
    CHECK(smartphone_ppp_policy(&opt).verified_repairs);
    smartphone_ppp_describe(&opt, text, sizeof(text));
    CHECK(strstr(text, "covariance=JOSEPH_B_MASK") != nullptr);
    fprintf(stderr, "%s\n", text);
    double h = 1, r = 1, x = 2, p = 1, v = NAN;
    CHECK(filter_verified(&x, &p, &h, &v, &r, 1, 1) == FILTER_NAN_INPUT && x == 2 && p == 1);
    v = INFINITY;
    CHECK(filter_verified(&x, &p, &h, &v, &r, 1, 1) == FILTER_NAN_INPUT && x == 2 && p == 1);
    v = 2; x = 1;
    CHECK(filter_verified(&x, &p, &h, &v, &r, 1, 1) == 0 && x == 2 && p == .5);
    const double bad_cov[] = {1, 2, 2, 1};
    CHECK(numeric_covariance_psd(bad_cov, 2) != 0);
    double xx[] = {1, 2}, pp[] = {1, 2, 2, 1}, hh[] = {1, 1};
    CHECK(filter_verified(xx, pp, hh, &v, &r, 2, 1) != 0 && xx[0] == 1 && xx[1] == 2 && pp[1] == 2);
    const double near[] = {1, 1 + 1e-15, 1 + 1e-15, 1};
    CHECK(numeric_covariance_psd(near, 2) == 0);
}

static bool parse(char *line, android_clock_t *clock, android_meas_t *m, const android_clock_t &last) {
    char *f[80]; int n = 0; char *p = line;
    while (n < 80) { f[n++] = p; p = strchr(p, ','); if (!p) break; *p++ = 0; }
    if (n < 37 || strcmp(f[0], "Raw")) return false;
    *clock = {}; *m = {};
    clock->time_nanos = strtoll(f[2], nullptr, 10);
    clock->full_bias_nanos = *f[5] ? strtoll(f[5], nullptr, 10) : last.full_bias_nanos;
    clock->has_full_bias = *f[5] || last.has_full_bias;
    clock->bias_nanos = *f[6] ? atof(f[6]) : last.bias_nanos;
    clock->hardware_clock_discontinuity_count = *f[10] ? atoi(f[10]) : last.hardware_clock_discontinuity_count;
    m->svid = atoi(f[11]); m->time_offset_nanos = atof(f[12]); m->state = atoi(f[13]);
    m->received_sv_time_nanos = strtoll(f[14], nullptr, 10);
    m->received_sv_time_uncertainty_nanos = atof(f[15]); m->cn0_dbhz = atof(f[16]);
    m->pseudorange_rate_mps = atof(f[17]); m->pseudorange_rate_uncertainty_mps = atof(f[18]);
    m->adr_state = atoi(f[19]); m->adr_meters = atof(f[20]); m->adr_uncertainty_meters = atof(f[21]);
    m->carrier_frequency_hz = atof(f[22]); m->constellation_type = atoi(f[28]);
    strncpy(m->code_type, f[35], sizeof(m->code_type) - 1);
    return true;
}

int main(int argc, char **argv) {
    numerical_tests();
    if (argc != 4 || failures) {
        fprintf(stderr, "Usage: verified_device_replay product-directory raw-file output-prefix\n");
        return failures ? 1 : 2;
    }
    const char *names[] = {
        "BRDC00WRD_S_20262910000_01D_MN.rnx", "WUM0MGXNRT_20262892200_02D_05M_ORB.SP3",
        "WUM0MGXNRT_20262892200_02D_30S_CLK.CLK", "WUM0MGXNRT_20262900200_01D_01D_OSB.BIA",
        "COD0OPSP1D_20262910000_01D_01H_GIM.INX", "VMF3_20261018.H00", "VMF3_20261018.H06",
        "orography_ell_5x5", "igs20_2438.atx"};
    std::vector<std::string> paths;
    for (auto n : names) paths.push_back(std::string(argv[1]) + "/" + n);
    traceopen((std::string(argv[3]) + ".trace").c_str()); tracelevel(2);
    CHECK(initialize_engine(paths));
    if (failures) { fprintf(stderr, "%s\n", g_engine.error.c_str()); return 1; }
    FILE *input = fopen(argv[2], "r"), *result = fopen((std::string(argv[3]) + ".csv").c_str(), "w");
    FILE *obs = fopen((std::string(argv[3]) + ".obs.csv").c_str(), "w");
    FILE *state = fopen((std::string(argv[3]) + ".stat").c_str(), "w");
    if (!input || !result || !obs || !state) return 3;
    fprintf(result, "week,tow,Q,ns,nobs,x_m,y_m,z_m,lat_deg,lon_deg,h_m,std_x_m,std_y_m,std_z_m,processing_ms\n");
    fprintf(obs, "week,tow,sat,signal,slot,P_m,L_cycle,D_Hz,CN0_dBHz,LLI,code\n");
    android_clock_t current{}, last{}, clock{}; android_meas_t m{};
    android_clock_t first_clock{}; std::vector<android_meas_t> first_raw;
    std::vector<android_meas_t> raw; std::vector<jdouble> first_output;
    int epochs = 0, q[8] = {0}, psd_bad = 0, finite_bad = 0;
    auto process = [&]() {
        std::vector<jdouble> out;
        bool ok = process_raw_epoch(current, raw, monotonic_ms(), &out);
        if (!ok) { fprintf(stderr, "epoch=%d failure=%s\n", epochs + 1, g_engine.error.c_str()); ++failures; return; }
        if (!epochs) { first_clock = current; first_raw = raw; first_output = out; }
        ++epochs; if (out[2] >= 0 && out[2] < 8) ++q[(int)out[2]];
        finite_bad += !numeric_finite(g_engine.rtk->x, g_engine.rtk->nx);
        psd_bad += numeric_covariance_psd(g_engine.rtk->P, g_engine.rtk->nx) != 0;
        fprintf(result, "%d,%.9f,%d,%d,%d,%.9f,%.9f,%.9f,%.12f,%.12f,%.6f,%.6f,%.6f,%.6f,%.3f\n",
                (int)out[0], out[1], (int)out[2], (int)out[3], (int)out[15], out[7], out[8], out[9],
                out[4], out[5], out[6], out[10], out[11], out[12], out[13]);
        fputs(g_engine.last_obs_csv.c_str(), obs);
        std::vector<char> buff(131072); int len = rtkoutstat(g_engine.rtk, 2, buff.data());
        if (len > 0 && len < (int)buff.size()) fwrite(buff.data(), 1, len, state);
        // Same epoch must not enter rtkpos twice or partially mutate x/P.
        if (epochs == 1) {
            std::vector<double> before_x(g_engine.rtk->x, g_engine.rtk->x + g_engine.rtk->nx);
            std::vector<double> before_p(g_engine.rtk->P, g_engine.rtk->P + g_engine.rtk->nx*g_engine.rtk->nx);
            CHECK(!process_raw_epoch(current, raw, monotonic_ms(), &out));
            CHECK(!memcmp(before_x.data(), g_engine.rtk->x, before_x.size()*sizeof(double)));
            CHECK(!memcmp(before_p.data(), g_engine.rtk->P, before_p.size()*sizeof(double)));
        }
    };
    char line[8192];
    while (fgets(line, sizeof(line), input)) {
        if (!parse(line, &clock, &m, last)) continue;
        if (!raw.empty() && current.time_nanos != clock.time_nanos) { process(); raw.clear(); }
        if (raw.empty()) current = clock;
        last = clock; raw.push_back(m);
    }
    if (!raw.empty()) process();
    fclose(input); fclose(result); fclose(obs); fclose(state);
    CHECK(epochs == 660); CHECK(psd_bad == 0); CHECK(finite_bad == 0);
    // A new native session must recreate the same first solution, not inherit
    // clock correction, adapter reset flags or old filter covariance.
    release_engine();
    CHECK(!g_engine.ready && !g_engine.rtk && !g_engine.nav);
    CHECK(initialize_engine(paths));
    std::vector<jdouble> fresh;
    CHECK(process_raw_epoch(first_clock, first_raw, monotonic_ms(), &fresh));
    CHECK(fresh.size() == first_output.size());
    for (int i = 0; i < (int)fresh.size(); ++i)
        if (i != 13) CHECK(fresh[i] == first_output[i]);
    release_engine();
    fprintf(stderr, "DEVICE_REPLAY,epochs=%d,Q0=%d,Q5=%d,Q6=%d,finite_bad=%d,PSD_bad=%d,checks=%d,failed=%d\n",
            epochs, q[0], q[5], q[6], finite_bad, psd_bad, checks, failures);
    return failures ? 1 : 0;
}
