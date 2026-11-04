/* Canonical smartphone PPP defaults. Vendor this file unchanged for Android. */
#ifndef SMARTPHONE_PPP_CONFIG_H
#define SMARTPHONE_PPP_CONFIG_H
#include "rtklib.h"
#ifdef __cplusplus
extern "C" {
#endif
#define SMARTPHONE_PPP_PROFILE "pc-baseline-20260930-v1"
typedef enum { PPP_BASELINE_B_LEGACY=0, PPP_BASELINE_A_CLEAN=1,
    MODE_C_NEW_ROBUST=2, MODE_D_ADAPTIVE_R=3 } ppp_baseline_t;
typedef enum { PPP_LIFECYCLE_LEGACY=0, PPP_LIFECYCLE_CORRECTED=1,
    PPP_LIFECYCLE_VERIFIED=2 } ppp_lifecycle_t;
typedef enum { PPP_CLOCK_AUTO=0,PPP_CLOCK_LEGACY_SPP=1,
    PPP_CLOCK_COMMON=2 } ppp_clock_mode_t;
typedef struct {
    int legacy_weighting, quality_learning, smoothing, atmosphere_constraints;
    int empirical_guards, startup_qc, corrected_lifecycle;
    int common_clock;
    int verified_repairs; /* explicit B_VERIFIED preset, not legacy B */
    double arc_gap_seconds; /* elapsed-time continuity bound, not maxout epochs */
} ppp_policy_t;
#define PPP_ARC_GAP_SECONDS_DEFAULT 30.0
#define PPP_HUBER_K_DEFAULT 1.5 /* dimensionless pre-fit u cutoff; no tuning */
/* Sole mode policy. C uses the clean model plus current-epoch Huber weights;
 * D remains reserved and is rejected. */
ppp_policy_t smartphone_ppp_policy(const prcopt_t *opt);
int smartphone_ppp_select(prcopt_t *opt, const char *baseline,
    const char *lifecycle, char *error, size_t capacity);
int smartphone_ppp_select_clock(prcopt_t *opt,const char *mode,char *error,size_t capacity);
/* B_VERIFIED integrates tested repairs without modifying stochastic parameters.
 * Select before loading observations/rtkinit; DEFAULT/empty preserves old modes. */
int smartphone_ppp_select_profile(prcopt_t *opt,const char *name,char *error,size_t capacity);
/* Profile and independent ablations; selectors are usable without environment/JNI. */
int smartphone_ppp_select_estimator(prcopt_t *opt,const char *profile,
    const char *rcb,const char *trop,const char *update,char *error,size_t capacity);
#define PPP_IEKF_MAX_ITER 10
#define PPP_IEKF_POSITION_TOL 0.001 /* m, numerical convergence; raPPPid coordinate criterion */
/* No products, state allocation or environment overrides are performed here. */
void smartphone_ppp_configure(prcopt_t *opt, solopt_t *sol);
/* Returns 0 with an explanation if compile-time layout/options conflict. */
int smartphone_ppp_validate(const prcopt_t *opt, char *error, size_t capacity);
/* Human-readable effective options for console, Android logcat and trace.
 * The signature covers the printed fields, not product identities or all of
 * prcopt_t. Never interpret equal signatures as proof of equal products. */
unsigned int smartphone_ppp_describe(const prcopt_t *opt, char *out, size_t capacity);
void smartphone_ppp_log(const char *entry, const prcopt_t *opt);
#ifdef __cplusplus
}
#endif
#endif
