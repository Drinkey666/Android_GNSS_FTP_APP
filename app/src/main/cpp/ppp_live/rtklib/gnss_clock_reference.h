#ifndef GNSS_CLOCK_REFERENCE_H
#define GNSS_CLOCK_REFERENCE_H
/* Zangenehnejad et al. 2023, s23020777 eq.2. No fitted correction, no resampling. */
#include "gnss_raw.h"
#include <stdint.h>
#include <limits.h>
#include <math.h>
#include <string.h>
typedef struct {
    int valid,have_clock,last_hcdc,segment,pending_reset;
    int64_t last_time,full_bias_ref;
    double bias_ref;
} gnss_clock_reference_t;
typedef struct { int64_t ns;double fraction_ns; } gnss_nanotime_t;

static int gnss_ns_add(int64_t a,int64_t b,int64_t *out) {
    if((b>0 && a>INT64_MAX-b)||(b<0 && a<INT64_MIN-b))return 0;
    *out=a+b;return 1;
}
static int gnss_ns_sub(int64_t a,int64_t b,int64_t *out) {
    if((b>0 && a<INT64_MIN+b)||(b<0 && a>INT64_MAX+b))return 0;
    *out=a-b;return 1;
}
static int64_t gnss_ns_mod(int64_t a,int64_t period) {
    int64_t r=a%period;return r<0?r+period:r;
}
static int gnss_receiver_nanotime(const android_clock_t *c,double offset,gnss_nanotime_t *t) {
    double bi,oi,frac;int64_t ns;
    if(!c || !c->has_full_bias || !isfinite(c->bias_nanos) || !isfinite(offset))return 0;
    bi=floor(c->bias_nanos);oi=floor(offset);
    if(bi<=-9223372036854775808. || bi>=9223372036854775808. ||
       oi<=-9223372036854775808. || oi>=9223372036854775808.)return 0;
    if(!gnss_ns_sub(c->time_nanos,c->full_bias_nanos,&ns)||
       !gnss_ns_sub(ns,(int64_t)bi,&ns)||!gnss_ns_add(ns,(int64_t)oi,&ns))return 0;
    frac=(offset-oi)-(c->bias_nanos-bi);
    if(frac<0){if(!gnss_ns_add(ns,-1,&ns))return 0;frac+=1.;}
    if(frac>=1){if(!gnss_ns_add(ns,1,&ns))return 0;frac-=1.;}
    if(ns<0)return 0;
    t->ns=ns;t->fraction_ns=frac;return 1;
}
static int gnss_clock_reference_apply(gnss_clock_reference_t *r,const android_clock_t *raw,android_clock_t *out) {
    android_clock_t candidate=*raw;gnss_nanotime_t t;
    if(raw->reference_segment>0) {
        *out=*raw;r->valid=raw->has_full_bias;r->have_clock=1;r->segment=raw->reference_segment;
        r->last_hcdc=raw->hardware_clock_discontinuity_count;r->last_time=raw->time_nanos;
        r->full_bias_ref=raw->full_bias_nanos;r->bias_ref=raw->bias_nanos;
        r->pending_reset=0;
        return r->valid;
    }
    int reset=raw->clock_discontinuity || (r->have_clock &&
        (raw->hardware_clock_discontinuity_count!=r->last_hcdc || raw->time_nanos<r->last_time));
    if(!r->have_clock)r->segment=1;
    else if(reset){r->segment++;r->valid=0;}
    if(reset)r->pending_reset=1;
    r->have_clock=1;r->last_hcdc=raw->hardware_clock_discontinuity_count;r->last_time=raw->time_nanos;
    candidate.clock_correction_nanos=0;
    if(!r->valid && gnss_receiver_nanotime(&candidate,0.,&t)) {
        r->full_bias_ref=raw->full_bias_nanos;r->bias_ref=raw->bias_nanos;r->valid=1;
    }
    *out=*raw;out->clock_correction_nanos=0;out->clock_discontinuity=reset || (r->valid && r->pending_reset);
    out->reference_segment=r->segment;
    out->has_full_bias=r->valid;
    if(r->valid){out->full_bias_nanos=r->full_bias_ref;out->bias_nanos=r->bias_ref;}
    if(r->valid)r->pending_reset=0;
    return r->valid;
}
#endif
