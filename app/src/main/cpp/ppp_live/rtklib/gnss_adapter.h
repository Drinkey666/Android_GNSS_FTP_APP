#ifndef GNSS_ADAPTER_H
#define GNSS_ADAPTER_H

#include "gnss_raw.h"
#include "gnss_clock_reference.h"
#include "rtklib.h"

enum {
    GNSS_RESET_CLOCK = 1,
    GNSS_RESET_ADR = 2,
    GNSS_RESET_SIGNAL = 4
};

typedef struct {
    int input_meas, unsupported, code_valid, phase_valid, doppler_valid;
    int invalid_time, duplicate, overflow;
    int phase_rejected_unc, phase_lli_slip;
    int lli_android, lli_clock;
    int invalid_clock,time_conflicts,phase_zero_sentinel;
} gnss_adapter_stats_t;

typedef struct {
    gnss_clock_reference_t clock_reference;
    int last_hcdc, have_hcdc;
    /* Reset evidence outlives a missing/rejected measurement. GNSS_RESET_* bits
     * are forwarded until P/L are emitted;
     * the PPP core then owns pending ambiguity initialization. */
    uint8_t pending_reset[MAXSAT][NFREQ];
    /* Highest handoff stage: 0=none, 1=no phase, 2=phase only. Do not count
     * retained evidence as a new slip on every missing-observation epoch. */
    uint8_t reset_forwarded[MAXSAT][NFREQ];
    uint8_t last_phase_code[MAXSAT][NFREQ];
    uint32_t last_adr_state[MAXSAT][NFREQ];
    uint8_t have_adr_state[MAXSAT][NFREQ];
    double last_phase_frequency[MAXSAT][NFREQ];
    double adr_unc_max;
    int adr_unc_off;
    int rinex_compatible;
    int ppp_safe;
    uint8_t rinex_code[3][NFREQ]; /* GPS, GAL, BDS; learned from rinex.c output */
} gnss_adapter_t;

void gnss_adapter_init(gnss_adapter_t *ctx);
void gnss_adapter_set_adr_unc_max(gnss_adapter_t *ctx, double max, int off);
void gnss_adapter_set_rinex_codes(gnss_adapter_t *ctx,
                                  const uint8_t codes[3][NFREQ]);
void gnss_adapter_set_ppp_safe(gnss_adapter_t *ctx, int enabled);
int gnss_adapter_map_signal(const android_meas_t *m, int *sat,
                            uint8_t *code, int *slot);
int gnss_adapter_clock_time(const android_clock_t *clock, gtime_t *time);
int gnss_adapter_convert(gnss_adapter_t *ctx, const android_clock_t *clock,
                         const android_meas_t *meas, int nmeas,
                         obsd_t *obs, int capacity,
                         gnss_adapter_stats_t *stats);

#endif
