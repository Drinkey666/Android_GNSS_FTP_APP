#ifndef ANDROID_B_VERIFIED_H
#define ANDROID_B_VERIFIED_H
#include "smartphone_ppp_config.h"

/* The Android app has exactly one public solver preset. No getenv(), no
 * copied stochastic parameters and no A/legacy/PHYSICAL UI switches. */
static inline int android_b_verified_configure(prcopt_t *opt, solopt_t *sol,
                                               char *error, size_t capacity)
{
    smartphone_ppp_configure(opt, sol);
    return smartphone_ppp_select_profile(opt, "B_VERIFIED", error, capacity) &&
           smartphone_ppp_validate(opt, error, capacity);
}
#endif
