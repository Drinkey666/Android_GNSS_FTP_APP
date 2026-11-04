#include "gnss_adapter.h"
#include "gnss_signal_policy.h"
#include <math.h>
#include <stdlib.h>
#include <string.h>

#define NS_WEEK 604800000000000LL
#define NS_HALF_WEEK 302400000000000LL
#define NS_BDS_GPS 14000000000LL
#define STATE_CODE_LOCK 1u
#define STATE_TOW_DECODED 8u
#define STATE_TOW_KNOWN 16384u

typedef struct { int sys, slot; uint8_t code; double freq; } signal_t;

static int near_frequency(double actual, double nominal)
{
    return isfinite(actual) && fabs(actual - nominal) < 10000.0;
}

static int signal_of(const android_meas_t *m, signal_t *s)
{
    double f = m->carrier_frequency_hz;
    char c = m->code_type[0];
    memset(s, 0, sizeof(*s));
    if(!isfinite(f) || f<=0.)return 0;
    if (m->constellation_type == 1) {
        s->sys = SYS_GPS;
        if (near_frequency(f, 1575420000.0) && c == 'C')
            s->slot = 0, s->code = CODE_L1C, s->freq = 1575420000.0;
        else if (near_frequency(f, 1176450000.0) && (c == 'I' || c == 'Q' || c == 'X'))
            s->slot = 2, s->code = obs2code(c == 'I' ? "5I" : c == 'Q' ? "5Q" : "5X"), s->freq = 1176450000.0;
    }
    else if (m->constellation_type == 6) {
        s->sys = SYS_GAL;
        if (near_frequency(f, 1575420000.0) && (c == 'C' || c == 'B' || c == 'X'))
            s->slot = 0, s->code = obs2code(c == 'C' ? "1C" : c == 'B' ? "1B" : "1X"), s->freq = 1575420000.0;
        else if (near_frequency(f, 1207140000.0) && (c == 'I' || c == 'Q' || c == 'X'))
            s->slot = 1, s->code = obs2code(c == 'I' ? "7I" : c == 'Q' ? "7Q" : "7X"), s->freq = 1207140000.0;
        else if (near_frequency(f, 1176450000.0) && (c == 'I' || c == 'Q' || c == 'X'))
            s->slot = 2, s->code = obs2code(c == 'I' ? "5I" : c == 'Q' ? "5Q" : "5X"), s->freq = 1176450000.0;
    }
    else if (m->constellation_type == 5) {
        s->sys = SYS_CMP;
        if (near_frequency(f, 1561098000.0) && (c == 'I' || c == 'Q' || c == 'X'))
            s->slot = 0, s->code = obs2code(c == 'I' ? "2I" : c == 'Q' ? "2Q" : "2X"), s->freq = 1561098000.0;
        else if (near_frequency(f, 1575420000.0) && (c == 'D' || c == 'P' || c == 'X'))
            s->slot = 0, s->code = obs2code(c == 'D' ? "1D" : c == 'P' ? "1P" : "1X"), s->freq = 1575420000.0;
        else if (near_frequency(f, 1207140000.0) && (c == 'I' || c == 'Q' || c == 'X'))
            s->slot = 1, s->code = obs2code(c == 'I' ? "7I" : c == 'Q' ? "7Q" : "7X"), s->freq = 1207140000.0;
        else if (near_frequency(f, 1176450000.0) && (c == 'D' || c == 'P' || c == 'Q' || c == 'X'))
            s->slot = 2, s->code = obs2code(c == 'D' ? "5D" : c == 'P' ? "5P" : c == 'Q' ? "5Q" : "5X"), s->freq = 1176450000.0;
    }
    else if(m->constellation_type==3 && (c=='C'||c=='P')) {
        if(!((f>1.59e9 && f<1.61e9)||(f>1.23e9 && f<1.26e9)))return 0;
        double base=f>1.59e9?1602e6:1246e6,spacing=f>1.59e9?.5625e6:.4375e6;
        int channel=(int)floor((f-base)/spacing+.5);
        double nominal=base+channel*spacing;
        if(channel>=-7 && channel<=6 && near_frequency(f,nominal)) {
            s->sys=SYS_GLO;s->slot=f>1.59e9?0:1;s->freq=nominal;
            s->code=obs2code(s->slot==0?(c=='C'?"1C":"1P"):(c=='C'?"2C":"2P"));
        }
    }
    return s->sys && s->code;
}

void gnss_adapter_init(gnss_adapter_t *ctx)
{
    memset(ctx, 0, sizeof(*ctx));
    ctx->adr_unc_max = 1.0;
}

void gnss_adapter_set_adr_unc_max(gnss_adapter_t *ctx, double max, int off)
{
    ctx->adr_unc_max = max;
    ctx->adr_unc_off = off;
}

void gnss_adapter_set_rinex_codes(gnss_adapter_t *ctx,
                                  const uint8_t codes[3][NFREQ])
{
    memcpy(ctx->rinex_code, codes, sizeof(ctx->rinex_code));
    ctx->rinex_compatible = 1;
}

void gnss_adapter_set_ppp_safe(gnss_adapter_t *ctx, int enabled)
{
    ctx->ppp_safe = enabled != 0;
}

static int permitted(const gnss_adapter_t *ctx, const signal_t *s)
{
    int sys = s->sys == SYS_GPS ? 0 : s->sys == SYS_GAL ? 1 :
              s->sys == SYS_CMP ? 2 : -1;
    if (ctx->ppp_safe)
        return gnss_ppp_signal_allowed(s->sys, s->slot, s->code);
    return !ctx->rinex_compatible ||
           (sys >= 0 && ctx->rinex_code[sys][s->slot] == s->code);
}

int gnss_adapter_map_signal(const android_meas_t *m, int *sat,
                            uint8_t *code, int *slot)
{
    signal_t s;
    int id;
    if (!signal_of(m, &s) || !(id = satno(s.sys, m->svid))) return 0;
    if (sat) *sat = id;
    if (code) *code = s.code;
    if (slot) *slot = s.slot;
    return 1;
}

int gnss_adapter_clock_time(const android_clock_t *clock, gtime_t *time)
{
    gnss_nanotime_t t;int week;int64_t tow;
    if(!gnss_receiver_nanotime(clock,0.,&t))return 0;
    week=(int)(t.ns/NS_WEEK);tow=t.ns%NS_WEEK;
    *time=gpst2time(week,(double)(tow/1000000000LL));
    time->sec+=((double)(tow%1000000000LL)+t.fraction_ns)*1e-9;
    return 1;
}

static int cmp_sat(const void *a, const void *b)
{
    return (int)((const obsd_t *)a)->sat - (int)((const obsd_t *)b)->sat;
}

int gnss_adapter_convert(gnss_adapter_t *ctx, const android_clock_t *clock,
                         const android_meas_t *meas, int nmeas,
                         obsd_t *obs, int capacity,
                         gnss_adapter_stats_t *stats)
{
    int i, j, n = 0, week, hcdc_changed, previous_segment,previous_reference_valid,time_conflict=0;
    int best[MAXSAT][NFREQ], best_score[MAXSAT][NFREQ];
    int best_priority[MAXSAT][NFREQ];
    android_clock_t referenced;
    gnss_nanotime_t receiver,actual_time[MAXSAT];
    uint8_t have_actual_time[MAXSAT]={0};
    double tow;
    gtime_t time;
    if (stats) memset(stats, 0, sizeof(*stats));
    if (!ctx || !clock) return 0;
    previous_segment=ctx->clock_reference.segment;
    previous_reference_valid=ctx->clock_reference.valid;
    gnss_clock_reference_apply(&ctx->clock_reference,clock,&referenced);
    clock=&referenced;
    if(clock->has_full_bias && (!previous_reference_valid || previous_segment!=ctx->clock_reference.segment))
        trace(2,"$OBS_CLOCK_REFERENCE,policy=FIXED_CLOCK_2023,segment=%d,hcdc=%d,full_bias_ref=%lld,bias_ref=%.17g\n",
              ctx->clock_reference.segment,clock->hardware_clock_discontinuity_count,
              (long long)clock->full_bias_nanos,clock->bias_nanos);
    /* Clock events belong to the receiver, even in an empty callback or one
     * whose timing/ADR cannot yet produce an observation. */
    hcdc_changed = clock->clock_discontinuity ||
        (ctx->have_hcdc &&
         clock->hardware_clock_discontinuity_count != ctx->last_hcdc);
    ctx->last_hcdc = clock->hardware_clock_discontinuity_count;
    ctx->have_hcdc = 1;
    if (hcdc_changed) {
        for (i=0;i<MAXSAT;i++) for (j=0;j<NFREQ;j++) {
            ctx->pending_reset[i][j] |= GNSS_RESET_CLOCK;
            ctx->reset_forwarded[i][j] = 0;
        }
        trace(2,"$ADAPTER_ARC_PENDING,reason=CLOCK_DISCONTINUITY,hcdc=%d\n",ctx->last_hcdc);
    }
    if (!meas || !obs || capacity <= 0 || nmeas <= 0) return 0;
    if(!clock->has_full_bias){if(stats)stats->invalid_clock++;return 0;}
    if(!gnss_adapter_clock_time(clock,&time)) {
        if(stats)stats->invalid_clock++;
        return 0;
    }
    for (i = 0; i < MAXSAT; i++) for (j = 0; j < NFREQ; j++) {
        best[i][j] = -1;
        best_score[i][j] = -1;
        best_priority[i][j] = -1;
    }
    /* Select one signal per RTKLIB frequency slot before touching arc state.
     * This makes B1I/B1C and repeated raw records independent of TXT order. */
    for (i = 0; i < nmeas; i++) {
        signal_t s;
        const android_meas_t *m = meas + i;
        int sat, score, priority;
        if (!signal_of(m, &s) || !permitted(ctx, &s) ||
            !(sat = satno(s.sys, m->svid))) continue;
        if(!gnss_receiver_nanotime(clock,m->time_offset_nanos,&receiver)) {
            if((m->adr_state&(ANDROID_ADR_RESET|ANDROID_ADR_SLIP)) &&
               (!ctx->last_phase_code[sat-1][s.slot] || ctx->last_phase_code[sat-1][s.slot]==s.code)) {
                ctx->pending_reset[sat-1][s.slot]|=GNSS_RESET_ADR;ctx->reset_forwarded[sat-1][s.slot]=0;
            }
            if(stats)stats->invalid_time++;continue;
        }
        priority = ctx->ppp_safe ?
            gnss_ppp_signal_priority(s.sys, s.slot, s.code) : 0;
        score = ((m->state & STATE_CODE_LOCK) &&
                 (m->state & (STATE_TOW_DECODED | STATE_TOW_KNOWN)) &&
                 m->received_sv_time_nanos > 0 &&
                 isfinite(m->received_sv_time_uncertainty_nanos) && m->received_sv_time_uncertainty_nanos>=0.0 &&
                 m->received_sv_time_uncertainty_nanos <= 500.0) ? 100 : 0;
        if ((m->adr_state & ANDROID_ADR_VALID) &&
            !(m->adr_state & (ANDROID_ADR_RESET | ANDROID_ADR_SLIP)) &&
            isfinite(m->adr_meters) && m->adr_meters != 0.0) score += 40;
        if (isfinite(m->pseudorange_rate_mps)) score += 10;
        if (!ctx->ppp_safe && s.sys == SYS_CMP && s.slot == 0 &&
            s.code == CODE_L2I) score += 5;
        if (ctx->ppp_safe && isfinite(m->cn0_dbhz) && m->cn0_dbhz > 0.0)
            score += (int)fmin(m->cn0_dbhz, 60.0);
        if (priority > best_priority[sat - 1][s.slot] ||
            (priority == best_priority[sat - 1][s.slot] &&
             score > best_score[sat - 1][s.slot])) {
            best_priority[sat - 1][s.slot] = priority;
            best_score[sat - 1][s.slot] = score;
            best[sat - 1][s.slot] = i;
        }
    }

    /* One obsd_t cannot represent different signal times within one satellite. */
    for(i=0;i<nmeas;i++) {
        signal_t sig;int sat;
        if(!signal_of(meas+i,&sig) || !permitted(ctx,&sig) || !(sat=satno(sig.sys,meas[i].svid)) ||
           best[sat-1][sig.slot]!=i)continue;
        if(!gnss_receiver_nanotime(clock,meas[i].time_offset_nanos,&receiver))continue;
        if(meas[i].adr_state&(ANDROID_ADR_RESET|ANDROID_ADR_SLIP)) {
            ctx->pending_reset[sat-1][sig.slot]|=GNSS_RESET_ADR;ctx->reset_forwarded[sat-1][sig.slot]=0;
        }
        if(have_actual_time[sat-1] && (actual_time[sat-1].ns!=receiver.ns || actual_time[sat-1].fraction_ns!=receiver.fraction_ns)) {
            if(stats)stats->time_conflicts++;
            trace(2,"$OBS_TIME_CONFLICT,sat=%d,reason=ASYNCHRONOUS_SIGNALS,raw_retained=1\n",sat);time_conflict=1;
        }
        actual_time[sat-1]=receiver;have_actual_time[sat-1]=1;
    }
    if(time_conflict)return 0; /* retain all selected reset evidence before declining an unsupported epoch */
    for (i = 0; i < nmeas; i++) {
        const android_meas_t *m = meas + i;
        signal_t sig;
        obsd_t *o;
        int sat, k, valid_adr, half_unresolved;
        int64_t flight_ns;
        double flight_s, lambda, q;
        if (stats) stats->input_meas++;
        if (!signal_of(m, &sig) || !permitted(ctx, &sig) || m->svid <= 0 ||
            !(sat = satno(sig.sys, m->svid))) {
            if (stats) stats->unsupported++;
            continue;
        }
        if (best[sat - 1][sig.slot] != i) {
            if (stats) stats->duplicate++;
            continue;
        }
        /* Latch before ADR gating or output-capacity checks. */
        if (m->adr_state & (ANDROID_ADR_RESET | ANDROID_ADR_SLIP)) {
            ctx->pending_reset[sat-1][sig.slot] |= GNSS_RESET_ADR;
            ctx->reset_forwarded[sat-1][sig.slot] = 0;
        }
        for (j = 0; j < n && obs[j].sat != sat; j++);
        if (j == n) {
            if (n >= capacity) { if (stats) stats->overflow++; continue; }
            memset(obs + n, 0, sizeof(obsd_t));
            obs[n].sat = (uint8_t)sat;
            obs[n].rcv = 1;
            {
                int64_t tns=actual_time[sat-1].ns;
                obs[n].time=gpst2time((int)(tns/NS_WEEK),(double)((tns%NS_WEEK)/1000000000LL));
                obs[n].time.sec+=((double)(tns%1000000000LL)+actual_time[sat-1].fraction_ns)*1e-9;
            }
            n++;
        }
        o = obs + j;
        k = sig.slot;
        o->code[k] = sig.code;
        lambda = CLIGHT / sig.freq;
        if (isfinite(m->cn0_dbhz) && m->cn0_dbhz > 0.0) {
            q = floor(m->cn0_dbhz / SNR_UNIT + 0.5);
            o->SNR[k] = (uint16_t)(q > 65535.0 ? 65535 : q);
        }
        {
            int known=sig.sys==SYS_GLO ? ((m->state&128u)||(m->state&32768u)) && (m->state&64u) :
                (m->state&STATE_CODE_LOCK) && (m->state&(STATE_TOW_DECODED|STATE_TOW_KNOWN));
            gnss_nanotime_t rx;int64_t period=sig.sys==SYS_GLO?86400000000000LL:NS_WEEK,rxmod;
            if(!gnss_receiver_nanotime(clock,m->time_offset_nanos,&rx)){if(stats)stats->invalid_time++;continue;}
            rxmod=gnss_ns_mod(rx.ns,period);
            if(sig.sys==SYS_CMP)rxmod=gnss_ns_mod(rxmod-NS_BDS_GPS,period);
            if(sig.sys==SYS_GLO) {
                int leap=clock->has_leap_seconds && clock->leap_seconds>=0 && clock->leap_seconds<=100?
                    clock->leap_seconds:(int)floor(timediff(o->time,gpst2utc(o->time))+.5);
                rxmod=gnss_ns_mod(rxmod+(10800LL-leap)*1000000000LL,period);
            }
            if(m->sv_time_status!=-1 && m->received_sv_time_nanos>=0 && m->received_sv_time_nanos<period && known &&
               isfinite(m->received_sv_time_uncertainty_nanos) && m->received_sv_time_uncertainty_nanos>=0. &&
               m->received_sv_time_uncertainty_nanos<=500.) {
                flight_ns=rxmod-m->received_sv_time_nanos;
                if(flight_ns>period/2)flight_ns-=period;
                if(flight_ns<-period/2)flight_ns+=period;
                flight_s=((double)flight_ns+rx.fraction_ns)*1e-9;
                if(flight_s>=.001 && flight_s<=.200){o->P[k]=flight_s*CLIGHT;if(stats)stats->code_valid++;}
                else {
                    if(stats)stats->invalid_time++;
                    trace(3,"$OBS_INVALID_CODE,sat=%d,signal=%s,reason=CODE_TIME_OUT_OF_RANGE,travel_s=%.12f,segment=%d\n",
                          sat,code2obs(sig.code),flight_s,ctx->clock_reference.segment);
                }
            }
        }
        if (isfinite(m->pseudorange_rate_mps) &&
            isfinite(m->pseudorange_rate_uncertainty_mps) &&
            m->pseudorange_rate_uncertainty_mps >= 0.0 &&
            m->pseudorange_rate_uncertainty_mps <= 10.0) {
            o->D[k] = (float)(-m->pseudorange_rate_mps / lambda);
            if (stats) stats->doppler_valid++;
        }
        valid_adr = (m->adr_state & ANDROID_ADR_VALID) != 0 && !(m->adr_state & (ANDROID_ADR_RESET|ANDROID_ADR_SLIP));
        half_unresolved = (m->adr_state & ANDROID_ADR_HALF_REPORTED) &&
                          !(m->adr_state & ANDROID_ADR_HALF_RESOLVED);
        if(ctx->have_adr_state[sat-1][k] &&
            (((ctx->last_adr_state[sat-1][k]^m->adr_state)&ANDROID_ADR_VALID) ||
             (((ctx->last_adr_state[sat-1][k]&ANDROID_ADR_HALF_REPORTED) &&
               !(ctx->last_adr_state[sat-1][k]&ANDROID_ADR_HALF_RESOLVED))!=half_unresolved))) {
            ctx->pending_reset[sat-1][k]|=GNSS_RESET_ADR;ctx->reset_forwarded[sat-1][k]=0;
        }
        ctx->have_adr_state[sat-1][k]=1;ctx->last_adr_state[sat-1][k]=m->adr_state;
        if (valid_adr && isfinite(m->adr_meters) &&
            m->adr_meters != 0.0 &&
            (ctx->adr_unc_off ||
             (isfinite(m->adr_uncertainty_meters) &&
              m->adr_uncertainty_meters >= 0.0 &&
              m->adr_uncertainty_meters <= ctx->adr_unc_max))) {
            o->L[k] = m->adr_meters / lambda;
            if (ctx->last_phase_code[sat-1][k] &&
                (ctx->last_phase_code[sat-1][k] != sig.code || ctx->last_phase_frequency[sat-1][k]!=sig.freq)) {
                ctx->pending_reset[sat-1][k] |= GNSS_RESET_SIGNAL;
                ctx->reset_forwarded[sat-1][k] = 0;
            }
            ctx->last_phase_code[sat-1][k] = sig.code;ctx->last_phase_frequency[sat-1][k]=sig.freq;
            if (half_unresolved) o->LLI[k] |= LLI_HALFC;
            if (stats) stats->phase_valid++;
        }
        else if (valid_adr && isfinite(m->adr_meters) &&
                 m->adr_meters != 0.0 && stats)
            stats->phase_rejected_unc++;
        if(valid_adr && m->adr_meters==0.0) {
            if(stats)stats->phase_zero_sentinel++;
            trace(3,"$OBS_PHASE_REPRESENTATION,sat=%d,signal=%s,reason=RTKLIB_ZERO_SENTINEL,raw_retained=1\n",sat,code2obs(sig.code));
        }
        if (ctx->pending_reset[sat-1][k]) {
            int stage=o->L[k]==0.0?1:(o->P[k]==0.0?2:3);
            if (stage>ctx->reset_forwarded[sat-1][k]) {
                o->LLI[k] |= LLI_SLIP;
                ctx->reset_forwarded[sat-1][k]=(uint8_t)stage;
                if (stats) {
                    if (ctx->pending_reset[sat-1][k]&GNSS_RESET_CLOCK) stats->lli_clock++;
                    if (ctx->pending_reset[sat-1][k]&GNSS_RESET_ADR) stats->lli_android++;
                }
                trace(3,"$ADAPTER_ARC_RESET,sat=%d,F%d,evidence=%u,phase=%d,code=%d\n",
                    sat,k+1,ctx->pending_reset[sat-1][k],o->L[k]!=0.0,o->P[k]!=0.0);
            }
            /* No engine acknowledgement exists at this boundary. Do not
             * consume evidence on a phase-only/empty row: keep forwarding it
             * until a complete row hands responsibility to core arc logic. */
            if (stage==3) {
                ctx->pending_reset[sat-1][k]=0;
                ctx->reset_forwarded[sat-1][k]=0;
            }
        }
        if (o->L[k] && (o->LLI[k] & LLI_SLIP) && stats)
            stats->phase_lli_slip++;
    }
    qsort(obs, n, sizeof(obsd_t), cmp_sat);
    return n;
}
