#include "smartphone_ppp_config.h"
#include <stdio.h>
#include <string.h>
#if NFREQ != 4
#error Smartphone PPP profile requires NFREQ=4 in every translation unit
#endif
#ifdef ENAQZS
#error Smartphone PPP profile uses the PC satellite layout without ENAQZS
#endif

#define PROFILE_OPTIONS \
    "-GAP_RESION=120 -IONCONS=1.5 -IONCONSINT=1 -VMF3SIG=0.15 " \
    "-VMF3ZWDSIG=0.30 -VMF3ZWDINT=300 -DOPPSM=0.90 -DOPPWARM=0 " \
    "-PREPROC=1 -DOPPSLIP=0.50 -CODEJUMP=30 -MWTHRES=5 " \
    "-BDSCODEVAR=1 -BDSCODEWARM=120 -WGTELCN=0 -PPPDIAG=1 " \
    "-PPPQUAR=3,120 -GALE5BPHASE=0"
typedef char profile_options_fit[
    sizeof(PROFILE_OPTIONS) <= sizeof(((prcopt_t *)0)->pppopt) ? 1 : -1];

ppp_policy_t smartphone_ppp_policy(const prcopt_t *opt)
{
    int legacy = opt && opt->ppp_baseline == PPP_BASELINE_B_LEGACY;
    ppp_policy_t policy = {0};
    const char *gap;
    double seconds;
    policy.legacy_weighting = policy.quality_learning = legacy;
    policy.smoothing = policy.atmosphere_constraints = legacy;
    policy.empirical_guards = policy.startup_qc = legacy;
    policy.corrected_lifecycle = !legacy ||
        opt->ppp_lifecycle == PPP_LIFECYCLE_CORRECTED ||
        opt->ppp_lifecycle == PPP_LIFECYCLE_VERIFIED;
    policy.verified_repairs=opt && opt->ppp_lifecycle==PPP_LIFECYCLE_VERIFIED;
    policy.common_clock=opt && (opt->ppp_clock_mode==PPP_CLOCK_COMMON ||
        (opt->ppp_clock_mode==PPP_CLOCK_AUTO && !legacy));
    policy.arc_gap_seconds=PPP_ARC_GAP_SECONDS_DEFAULT;
    if(opt && (gap=strstr(opt->pppopt,"-ARCGAP=")) &&
        sscanf(gap+8,"%lf",&seconds)==1 && isfinite(seconds) && seconds>0.0)
        policy.arc_gap_seconds=seconds;
    return policy;
}

static int phase_identity_selected(const char *options)
{
    const char *p=options,*flag="-PPP_PHASE_ID";
    size_t n=strlen(flag);
    if(!p)return 0;
    while((p=strstr(p,flag))) {
        if((p==options || p[-1]==' ') && (!p[n] || p[n]==' '))return 1;
        p+=n;
    }
    return 0;
}

int smartphone_ppp_select_profile(prcopt_t *opt,const char *name,char *error,size_t capacity)
{
    const char *problem=NULL;const char *flag="-PPP_PHASE_ID";
    if(!opt)problem="null processing options";
    else if(!name || !*name || !strcmp(name,"DEFAULT")) { /* preserve explicit old options */ }
    else if(strcmp(name,"B_VERIFIED"))problem="RTK_PPP_PROFILE must be DEFAULT or B_VERIFIED";
    else if(opt->ppp_rcb_datum || opt->ppp_wet_correction || opt->ppp_iekf || opt->ppp_physical_atmosphere)
        problem="B_VERIFIED requires the LEGACY estimator; do not combine PHYSICAL/PAIR_DATUM/IEKF";
    else if(!phase_identity_selected(opt->rnxopt[0]) &&
        strlen(opt->rnxopt[0])+strlen(flag)+(*opt->rnxopt[0]?1:0)+1>sizeof(opt->rnxopt[0]))
        problem="RINEX option buffer too small for B_VERIFIED phase identity";
    else {
        opt->ppp_baseline=PPP_BASELINE_B_LEGACY;
        opt->ppp_lifecycle=PPP_LIFECYCLE_VERIFIED;
        opt->ppp_clock_mode=PPP_CLOCK_COMMON;
        if(!phase_identity_selected(opt->rnxopt[0])) {
            if(*opt->rnxopt[0])strcat(opt->rnxopt[0]," ");
            strcat(opt->rnxopt[0],flag);
        }
    }
    if(error && capacity)snprintf(error,capacity,"%s",problem?problem:"");
    return !problem;
}

int smartphone_ppp_select(prcopt_t *opt, const char *baseline,
    const char *lifecycle, char *error, size_t capacity)
{
    const char *problem = NULL;
    if (baseline && *baseline) {
        if (!strcmp(baseline,"A") || !strcmp(baseline,"CLEAN_BASELINE"))
            opt->ppp_baseline = PPP_BASELINE_A_CLEAN;
        else if (!strcmp(baseline,"B") || !strcmp(baseline,"LEGACY_BASELINE"))
            opt->ppp_baseline = PPP_BASELINE_B_LEGACY;
        else if (!strcmp(baseline,"C") || !strcmp(baseline,"HUBER_ROBUST"))
            opt->ppp_baseline = MODE_C_NEW_ROBUST;
        else problem = "baseline must be A, B or C; D is reserved and unavailable";
    }
    if (!problem && lifecycle && *lifecycle) {
        if (!strcmp(lifecycle,"LEGACY")) opt->ppp_lifecycle=PPP_LIFECYCLE_LEGACY;
        else if (!strcmp(lifecycle,"CORRECTED")) opt->ppp_lifecycle=PPP_LIFECYCLE_CORRECTED;
        else problem = "lifecycle must be LEGACY or CORRECTED";
        if (!problem && opt->ppp_baseline!=PPP_BASELINE_B_LEGACY &&
            opt->ppp_lifecycle==PPP_LIFECYCLE_LEGACY)
            problem = "A/C require CORRECTED lifecycle";
    }
    if (error && capacity) snprintf(error,capacity,"%s",problem?problem:"");
    return !problem;
}

void smartphone_ppp_configure(prcopt_t *opt, solopt_t *sol)
{
    *opt = prcopt_default;
    opt->mode = PMODE_PPP_STATIC;
    opt->dynamics = 0;
    opt->navsys = SYS_GPS | SYS_GAL | SYS_CMP;
    opt->nf = 4;
    opt->sateph = EPHOPT_PREC;
    opt->ionoopt = IONOOPT_EST;
    opt->tropopt = TROPOPT_EST;
    opt->err[1] = 0.008; opt->err[2] = 0.012;
    opt->eratio[0] = 100.0; opt->eratio[1] = 70.0;
    opt->eratio[2] = 60.0; opt->eratio[3] = 100.0;
    opt->modear = ARMODE_OFF;
    opt->thresslip = 0.20;
    opt->elmin = 15.0 * D2R;
    opt->prn[0] = 1e-4; opt->prn[1] = 1e-3; opt->prn[2] = 1e-4;
    opt->maxout = 30;
    opt->tidecorr = 1;
    opt->posopt[0] = 1; opt->posopt[1] = 0;
    opt->posopt[2] = 1; opt->posopt[3] = 1;
    opt->snrmask.ena[0] = 0;
    memcpy(opt->pppopt, PROFILE_OPTIONS, sizeof(PROFILE_OPTIONS));
    if (sol) {
        *sol = solopt_default;
        sol->sstat = 2;
        sol->maxsolstd = 0.0;
    }
}

int smartphone_ppp_select_clock(prcopt_t *opt,const char *mode,char *error,size_t capacity)
{
    const char *problem=NULL;
    if(!opt)problem="null processing options";
    else if(mode && *mode) {
        if(!strcmp(mode,"AUTO"))opt->ppp_clock_mode=PPP_CLOCK_AUTO;
        else if(!strcmp(mode,"LEGACY_SPP"))opt->ppp_clock_mode=PPP_CLOCK_LEGACY_SPP;
        else if(!strcmp(mode,"PPP_COMMON"))opt->ppp_clock_mode=PPP_CLOCK_COMMON;
        else problem="PPP clock must be AUTO, LEGACY_SPP or PPP_COMMON";
    }
    if(error && capacity)snprintf(error,capacity,"%s",problem?problem:"");
    return problem==NULL;
}

int smartphone_ppp_select_estimator(prcopt_t *opt,const char *profile,
    const char *rcb,const char *trop,const char *update,char *error,size_t capacity)
{
    const char *problem=NULL;
    if(!opt)problem="null processing options";
    else {
        if(profile && *profile) {
            if(!strcmp(profile,"LEGACY")) {
                opt->ppp_rcb_datum=opt->ppp_wet_correction=opt->ppp_iekf=0;
                opt->ppp_physical_atmosphere=0;
            }
            else if(!strcmp(profile,"INTEGRATED")) {
                opt->ppp_rcb_datum=opt->ppp_wet_correction=opt->ppp_iekf=1;
                opt->ppp_physical_atmosphere=0;
            }
            else if(!strcmp(profile,"PHYSICAL")) {
                opt->ppp_rcb_datum=0;opt->ppp_wet_correction=opt->ppp_iekf=1;
                opt->ppp_physical_atmosphere=1;
            }
            else problem="estimator must be LEGACY, INTEGRATED or PHYSICAL";
        }
        if(!problem && rcb && *rcb) {
            if(!strcmp(rcb,"LEGACY"))opt->ppp_rcb_datum=0;
            else if(!strcmp(rcb,"PAIR_DATUM"))opt->ppp_rcb_datum=1;
            else problem="RCB must be LEGACY or PAIR_DATUM";
        }
        if(!problem && trop && *trop) {
            if(!strcmp(trop,"TOTAL_ZTD"))opt->ppp_wet_correction=0;
            else if(!strcmp(trop,"WET_CORRECTION"))opt->ppp_wet_correction=1;
            else problem="trop state must be TOTAL_ZTD or WET_CORRECTION";
        }
        if(!problem && update && *update) {
            if(!strcmp(update,"EKF"))opt->ppp_iekf=0;
            else if(!strcmp(update,"IEKF"))opt->ppp_iekf=1;
            else problem="update must be EKF or IEKF";
        }
    }
    if(error && capacity)snprintf(error,capacity,"%s",problem?problem:"");
    return !problem;
}

int smartphone_ppp_validate(const prcopt_t *opt, char *error, size_t capacity)
{
    const char *problem = NULL;
    int supported = SYS_GPS;
    if (NSATGLO) supported |= SYS_GLO;
    if (NSATGAL) supported |= SYS_GAL;
    if (NSATCMP) supported |= SYS_CMP;
    if (NSATQZS) supported |= SYS_QZS;
    if (!opt) problem = "null processing options";
    else if (opt->ppp_baseline < 0 || opt->ppp_baseline > MODE_C_NEW_ROBUST)
        problem = "D algorithm is not implemented";
    else if (opt->ppp_lifecycle < 0 || opt->ppp_lifecycle > PPP_LIFECYCLE_VERIFIED)
        problem = "invalid PPP lifecycle";
    else if(opt->ppp_lifecycle==PPP_LIFECYCLE_VERIFIED &&
        (opt->ppp_baseline!=PPP_BASELINE_B_LEGACY || opt->ppp_clock_mode!=PPP_CLOCK_COMMON ||
         opt->ppp_rcb_datum || opt->ppp_wet_correction || opt->ppp_iekf || opt->ppp_physical_atmosphere ||
         !phase_identity_selected(opt->rnxopt[0])))
        problem="incomplete B_VERIFIED preset; use smartphone_ppp_select_profile before input loading";
    else if(opt->ppp_clock_mode<PPP_CLOCK_AUTO || opt->ppp_clock_mode>PPP_CLOCK_COMMON)
        problem="invalid PPP clock policy";
    else if(opt->ppp_rcb_datum<0 || opt->ppp_rcb_datum>1 ||
        opt->ppp_wet_correction<0 || opt->ppp_wet_correction>1 || opt->ppp_iekf<0 || opt->ppp_iekf>1)
        problem="invalid estimator profile";
    else if(opt->ppp_physical_atmosphere<0 || opt->ppp_physical_atmosphere>1)
        problem="invalid physical atmosphere policy";
    else if(opt->ppp_physical_atmosphere && (opt->ppp_rcb_datum ||
        opt->ionoopt!=IONOOPT_EST || opt->nf<3 ||
        (opt->navsys & ~(SYS_GPS|SYS_GLO|SYS_GAL|SYS_CMP))))
        problem="PHYSICAL requires physical ionosphere, relative RCB and supported noncombined signals";
    else if(opt->ppp_physical_atmosphere && opt->ppp_baseline==PPP_BASELINE_B_LEGACY)
        problem="PHYSICAL is an independent A/C model; B retains historical atmosphere";
    else if(opt->ppp_gal_e5b<0 || opt->ppp_gal_e5b>1)
        problem="GAL_E5B must be 0 or 1";
    else if(opt->ppp_gal_e5b && (opt->ionoopt!=IONOOPT_EST || opt->nf<3 || !(opt->navsys&SYS_GAL)))
        problem="GAL_E5B requires noncombined Galileo with nf>=3";
    else if(opt->ppp_rcb_datum && (opt->ionoopt!=IONOOPT_EST || opt->nf<3 ||
        (opt->navsys & ~(SYS_GPS|SYS_GLO|SYS_GAL|SYS_CMP))))
        problem="PAIR_DATUM requires noncombined GPS/GAL/BDS or single-G1 GLONASS with nf>=3";
    else if(opt->ppp_rcb_datum && smartphone_ppp_policy(opt).atmosphere_constraints)
        problem="PAIR_DATUM effective ionosphere cannot use B physical IONEX constraints; choose A/C or LEGACY RCB";
    else if(opt->ppp_wet_correction && opt->tropopt<TROPOPT_EST)
        problem="WET_CORRECTION requires estimated troposphere";
    else if(opt->ppp_iekf && opt->ppp_baseline==PPP_BASELINE_B_LEGACY)
        problem="IEKF is an A/C experiment; B weighting must retain its historical update";
    else if (opt->nf < 1 || opt->nf > NFREQ)
        problem = "processing frequency count exceeds compiled observation layout";
    else if (!opt->navsys || (opt->navsys & ~supported))
        problem = "requested satellite system is not compiled";
    else if (!memchr(opt->pppopt, 0, sizeof(opt->pppopt)))
        problem = "PPP option string is not terminated";
    else if (!numeric_finite(opt->err,8) || !numeric_finite(opt->eratio,NFREQ) ||
        !numeric_finite(opt->prn,6) || !isfinite(opt->thresslip) || !isfinite(opt->elmin))
        problem = "nonfinite PPP stochastic model parameter";
    if (!problem) {
        const char *gap=strstr(opt->pppopt,"-ARCGAP=");double seconds;
        if(gap && (sscanf(gap+8,"%lf",&seconds)!=1 || !isfinite(seconds) || seconds<=0))
            problem="ARCGAP must be a positive finite interval in seconds";
    }
    if (error && capacity) snprintf(error, capacity, "%s", problem ? problem : "");
    return problem == NULL;
}

unsigned int smartphone_ppp_describe(const prcopt_t *opt, char *out, size_t capacity)
{
    /* Canonical text avoids platform-dependent struct padding in signatures. */
    char text[2048];
    unsigned int hash = 2166136261u;
    const unsigned char *p;
    snprintf(text, sizeof(text),
        "baseline=%s lifecycle=%s arc_gap_sec=%.9g eta=1 profile=%s NFREQ=%d MAXSAT=%d NSATGLO=%d NSATQZS=%d "
        "mode=%d soltype=%d nf=%d navsys=%d dynamics=%d sateph=%d "
        "iono=%d trop=%d ar=%d elmin=%.9g maxout=%d tide=%d "
        "err=%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g,%.9g "
        "eratio=%.9g,%.9g,%.9g,%.9g,%.9g,%.9g "
        "prn=%.9g,%.9g,%.9g,%.9g,%.9g,%.9g "
        "std=%.9g,%.9g,%.9g slip=%.9g dop=%.9g "
        "maxinno=%.9g,%.9g niter=%d codesmooth=%d "
        "posopt=%d,%d,%d,%d,%d,%d snrmask=%d,%d pppopt=%s",
        opt->ppp_baseline==PPP_BASELINE_A_CLEAN?"A_CLEAN":
        opt->ppp_baseline==MODE_C_NEW_ROBUST?"C_HUBER":"B_LEGACY",
        smartphone_ppp_policy(opt).corrected_lifecycle?"CORRECTED":"LEGACY",
        smartphone_ppp_policy(opt).arc_gap_seconds,
        SMARTPHONE_PPP_PROFILE, NFREQ, MAXSAT, NSATGLO, NSATQZS,
        opt->mode, opt->soltype, opt->nf, opt->navsys, opt->dynamics,
        opt->sateph, opt->ionoopt, opt->tropopt, opt->modear,
        opt->elmin, opt->maxout, opt->tidecorr,
        opt->err[0], opt->err[1], opt->err[2], opt->err[3],
        opt->err[4], opt->err[5], opt->err[6], opt->err[7],
        opt->eratio[0], opt->eratio[1], opt->eratio[2],
        opt->eratio[3], opt->eratio[4], opt->eratio[5],
        opt->prn[0], opt->prn[1], opt->prn[2],
        opt->prn[3], opt->prn[4], opt->prn[5],
        opt->std[0], opt->std[1], opt->std[2], opt->thresslip, opt->thresdop,
        opt->maxinno[0], opt->maxinno[1], opt->niter, opt->codesmooth,
        opt->posopt[0], opt->posopt[1], opt->posopt[2],
        opt->posopt[3], opt->posopt[4], opt->posopt[5],
        opt->snrmask.ena[0], opt->snrmask.ena[1], opt->pppopt);
    if(opt->ppp_baseline==MODE_C_NEW_ROBUST) {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," huber_k=%.9g",PPP_HUBER_K_DEFAULT);
    }
    {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," clock_seed=%s clock_mode=%d",
            smartphone_ppp_policy(opt).common_clock?"PPP_COMMON":"LEGACY_SPP",opt->ppp_clock_mode);
    }
    if(opt->ppp_rcb_datum || opt->ppp_wet_correction || opt->ppp_iekf) {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," rcb=%s trop_state=%s update=%s",
            opt->ppp_rcb_datum?"PAIR_DATUM":"LEGACY",
            opt->ppp_wet_correction?"WET_CORRECTION":"TOTAL_ZTD",opt->ppp_iekf?"IEKF":"EKF");
    }
    if(opt->ppp_physical_atmosphere) {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," atmosphere=PHYSICAL iono=VERTICAL_L1 rcb_reference=GPS_1C/GAL_1C/BDS_2I/GLO_1C gim_information=MAP_CADENCE wet_prior=INITIAL_ONLY");
    }
    if(opt->ppp_gal_e5b) {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," gal_e5b=CODE_PHASE_RCB");
    }
    if(smartphone_ppp_policy(opt).verified_repairs) {
        size_t len=strlen(text);
        snprintf(text+len,sizeof(text)-len," repairs=B_VERIFIED_v2 phase_identity=1 reference_seed=1 ion_continuity=1 reference_event=1 covariance=JOSEPH_B_MASK");
    }
    for (p = (const unsigned char *)text; *p; ++p) hash = (hash ^ *p) * 16777619u;
    if (out && capacity) snprintf(out, capacity, "PPP_CONFIG,signature=%08x,%s", hash, text);
    return hash;
}

void smartphone_ppp_log(const char *entry, const prcopt_t *opt)
{
    char description[2048];
    ppp_policy_t p=smartphone_ppp_policy(opt);
    smartphone_ppp_describe(opt, description, sizeof(description));
    fprintf(stderr, "%s,entry=%s\n", description, entry);
    trace(2, "%s,entry=%s\n", description, entry);
    fprintf(stderr,"PPP_POLICY,legacy_weighting=%d,quality_learning=%d,smoothing=%d,atmosphere_constraints=%d,empirical_guards=%d,startup_qc=%d,corrected_lifecycle=%d,eta=1\n",
        p.legacy_weighting,p.quality_learning,p.smoothing,p.atmosphere_constraints,
        p.empirical_guards,p.startup_qc,p.corrected_lifecycle);
}
