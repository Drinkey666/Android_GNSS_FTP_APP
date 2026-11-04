/* PPP-only position controller, adapted from RTKLIB rtkpos.c.
 * Copyright (C) 2007-2020 by T. Takasu, All rights reserved.
 */
#include <stdarg.h>
#include "rtklib.h"
#include "ppp_innovation_diag.h"
#include "smartphone_ppp_config.h"

#define STD_PREC_VAR_THRESH 0
#define NF(opt) ((opt)->ionoopt==IONOOPT_IFLC?1:(opt)->nf)

static int statlevel=0;
static FILE *fp_stat=NULL;
static char file_stat[1024]="";
static gtime_t time_stat={0};
extern int rtkopenstat(const char* file, int level)
{
    gtime_t time = utc2gpst(timeget());
    char path[1024];

    trace(3, "rtkopenstat: file=%s level=%d\n", file, level);

    if (level <= 0) return 0;

    reppath(file, path, time, "", "");

    if (!(fp_stat = fopen(path, "w"))) {
        trace(1, "rtkopenstat: file open error path=%s\n", path);
        return 0;
    }
    strcpy(file_stat, file);
    time_stat = time;
    statlevel = level;
    return 1;
}
/* close solution status file --------------------------------------------------
* close solution status file
* args   : none
* return : none
*-----------------------------------------------------------------------------*/
extern void rtkclosestat(void)
{
    trace(3, "rtkclosestat:\n");

    if (fp_stat) fclose(fp_stat);
    fp_stat = NULL;
    file_stat[0] = '\0';
    statlevel = 0;
}
/* Write solution status to buffer -------------------------------------------*/
/* PPP state and residual records retain the RTKLIB output format. */
extern int rtkoutstat(rtk_t *rtk,int level,char *buff)
{
    ssat_t *ssat;
    int week,nfreq,i,j,k;
    double tow;
    char id[8],*p=buff;

    if(level<=0||rtk->sol.stat==SOLQ_NONE)return 0;
    nfreq=NF(&rtk->opt);
    tow=time2gpst(rtk->sol.time,&week);
    p+=pppoutstat(rtk,buff);
    if(level<=1)return (int)(p-buff);

    for(i=0;i<MAXSAT;i++){
        ssat=rtk->ssat+i;
        if(!ssat->vs) {
            int accepted=0;
            if(smartphone_ppp_policy(&rtk->opt).verified_repairs)
                for(j=0;j<nfreq && j<NFREQ;j++)if(ssat->vsat[j])accepted=1;
            if(!accepted)continue;
        }
        satno2id(i+1,id);
        for(j=0;j<nfreq;j++){
            k=pppambidx(i+1,j,&rtk->opt);
            p+=sprintf(p,"$SAT,%d,%.3f,%s,%d,%.1f,%.1f,%.4f,%.4f,%d,%.0f,%d,%d,%d,%u,%u,%u,%.2f,%.6f,%.5f\n",
                week,tow,id,j+1,ssat->azel[0]*R2D,ssat->azel[1]*R2D,
                ssat->resp[j],ssat->resc[j],ssat->vsat[j],ssat->snr_rover[j]*SNR_UNIT,
                ssat->fix[j],ssat->slip[j]&(LLI_SLIP|LLI_HALFC),ssat->lock[j],ssat->outc[j],
                ssat->slipc[j],ssat->rejc[j],k<rtk->nx?rtk->x[k]:0,
                k<rtk->nx?rtk->P[k+k*rtk->nx]:0,ssat->icbias[j]);
        }
    }
    return (int)(p-buff);
}/* swap solution status file -------------------------------------------------*/
static void swapsolstat(void)
{
    gtime_t time = utc2gpst(timeget());
    char path[1024];

    if ((int)(time2gpst(time, NULL) / INT_SWAP_STAT) ==
        (int)(time2gpst(time_stat, NULL) / INT_SWAP_STAT)) {
        return;
    }
    time_stat = time;

    if (!reppath(file_stat, path, time, "", "")) {
        return;
    }
    if (fp_stat) fclose(fp_stat);

    if (!(fp_stat = fopen(path, "w"))) {
        trace(2, "swapsolstat: file open error path=%s\n", path);
        return;
    }
    trace(3, "swapsolstat: path=%s\n", path);
}
/* output solution status ----------------------------------------------------*/
static void outsolstat(rtk_t* rtk, const nav_t* nav)
{
    if (statlevel <= 0 || !fp_stat || !rtk->sol.stat) return;

    trace(3, "outsolstat:\n");

    /* swap solution status file */
    swapsolstat();

    /* write solution status */
    char buff[MAXSOLMSG + 1];
    int n = rtkoutstat(rtk, statlevel, buff);
    buff[n] = '\0';

    fputs(buff, fp_stat);
}
/* save error message --------------------------------------------------------*/
static void errmsg(rtk_t* rtk, const char* format, ...)
{
    char buff[384], tstr[40];
    int n;
    va_list ap;
    time2str(rtk->sol.time, tstr, 2);
    n = sprintf(buff, "%s: ", tstr + 11);
    va_start(ap, format);
    n += vsprintf(buff + n, format, ap);
    va_end(ap);
    n = n < MAXERRMSG - rtk->neb ? n : MAXERRMSG - rtk->neb;
    memcpy(rtk->errbuf + rtk->neb, buff, n);
    rtk->neb += n;
    trace(2, "%s", buff);
}
extern void rtkinit(rtk_t* rtk, const prcopt_t* opt)
{
    sol_t sol0 = { {0} };
    ambc_t ambc0 = { {{0}} };
    ssat_t ssat0 = { 0 };
    int i;

    trace(3, "rtkinit :\n");

    /* Copy and validate options before calculating state dimensions. */
    rtk->opt = *opt;
    if (rtk->opt.nf < 1) rtk->opt.nf = 1;
    if (rtk->opt.nf > NFREQ) rtk->opt.nf = NFREQ;

    rtk->sol = sol0;
    for (i = 0; i < 6; i++) rtk->rb[i] = 0.0;
    rtk->nx = pppnx(&rtk->opt);
    rtk->na = pppnx(&rtk->opt);
    rtk->tt = 0.0;
    rtk->epoch = 0;
    rtk->ppp_failure = 0;
    /* New session: never inherit a model basis lock or atmospheric history. */
    rtk->ppp_estimator_flags = rtk->ppp_estimator_locked = 0;
    rtk->ppp_trop_base = rtk->ppp_trop_zhd = 0.0;
    rtk->ppp_trop_time.time = 0;
    rtk->ppp_trop_time.sec = 0.0;
    rtk->ppp_clock_valid=rtk->ppp_spp_clock_valid=0;
    rtk->ppp_clock_m=rtk->ppp_spp_clock_m=0.0;
    rtk->ppp_clock_time.time=0;rtk->ppp_clock_time.sec=0.0;
    rtk->ppp_start_time.time = 0;
    rtk->ppp_start_time.sec = 0.0;
    rtk->ppp_zwd_prior_time.time = 0;
    rtk->ppp_zwd_prior_time.sec = 0.0;
    rtk->ppp_start_valid = rtk->ppp_code_warmup = rtk->ppp_phase_started = 0;
    rtk->x = zeros(rtk->nx, 1);
    rtk->P = zeros(rtk->nx, rtk->nx);
    rtk->xa = zeros(rtk->na, 1);
    rtk->Pa = zeros(rtk->na, rtk->na);
    rtk->nfix = rtk->neb = 0;
    for (i = 0; i < MAXSAT; i++) {
        rtk->ambc[i] = ambc0;
        rtk->ssat[i] = ssat0;
    }
    rtk->holdamb = 0;
    rtk->excsat = 0;
    rtk->nb_ar = 0;
    for (i = 0; i < MAXERRMSG; i++) rtk->errbuf[i] = 0;

    rtk->initial_mode = rtk->opt.mode;
    rtk->sol.thres = (float)rtk->opt.thresar[0];
    rtk->intpres_nb = 0;

    /* Do not modify ionoopt/nf/std/prn/maxinno here. Changing options after
       nx/na allocation changes the PPP state layout and can cause OOB access. */
}
/* free rtk control ------------------------------------------------------------
* free memory for rtk control struct
* args   : rtk_t    *rtk    IO  rtk control/result struct
* return : none
*-----------------------------------------------------------------------------*/
extern void rtkfree(rtk_t* rtk)
{
    trace(3, "rtkfree :\n");

    rtk->nx = rtk->na = 0;
    free(rtk->x); rtk->x = NULL;
    free(rtk->P); rtk->P = NULL;
    free(rtk->xa); rtk->xa = NULL;
    free(rtk->Pa); rtk->Pa = NULL;
}
/* Internal startup entry: no public structure/API changes. */
extern int pntpos_startup(const obsd_t *obs,int n,const nav_t *nav,
    const prcopt_t *opt,sol_t *sol,double *azel,ssat_t *ssat,char *msg);

static int startup_qc_enabled(const prcopt_t *opt)
{
    const char *p;double pre=0.0,start=1.0;
    if(!smartphone_ppp_policy(opt).startup_qc)return 0;
    if((p=strstr(opt->pppopt,"-PREPROC=")))sscanf(p+9,"%lf",&pre);
    if((p=strstr(opt->pppopt,"-STARTQC=")))sscanf(p+9,"%lf",&start);
    if(!isfinite(pre))pre=0.0;
    if(!isfinite(start))start=1.0;
    return opt->mode>=PMODE_PPP_KINEMA && pre>=0.5 && start>=0.5;
}
/* Only clean a private copy: missing code must not delete a valid carrier. */
static int sanitize_ppp_input(const obsd_t *in,int n,obsd_t *out)
{
    int i,f,k=0;char t[40];
    for(i=0;i<n;i++) {
        if(in[i].rcv!=1)continue;
        if(in[i].sat<1||in[i].sat>MAXSAT) {
            trace(2,"$PRE_INPUT_REJECT,sat=%d,reason=SAT_ID\n",in[i].sat);continue;
        }
        if(k>=MAXOBS){trace(2,"$PRE_INPUT_REJECT,reason=MAXOBS\n");break;}
        out[k]=in[i];time2str(in[i].time,t,3);
        for(f=0;f<NFREQ+NEXOBS;f++) {
            if(!isfinite(out[k].P[f]) || (out[k].P[f]!=0.0 &&
                (out[k].P[f]<1E6||out[k].P[f]>1E8))) {
                trace(2,"$PRE_INPUT_REJECT,%s,sat=%d,F%d,type=P,reason=NONFINITE_OR_RANGE\n",t,in[i].sat,f+1);
                out[k].P[f]=0.0;
            }
            if(!isfinite(out[k].L[f])||fabs(out[k].L[f])>1E12) {
                trace(2,"$PRE_INPUT_REJECT,%s,sat=%d,F%d,type=L,reason=NONFINITE_OR_RANGE\n",t,in[i].sat,f+1);
                out[k].L[f]=0.0;out[k].LLI[f]|=LLI_SLIP;
            }
            if(!isfinite(out[k].D[f])||fabs(out[k].D[f])>20000.0) {
                trace(2,"$PRE_INPUT_REJECT,%s,sat=%d,F%d,type=D,reason=NONFINITE_OR_RANGE\n",t,in[i].sat,f+1);
                out[k].D[f]=0.0;
            }
        }
        k++;
    }
    return k;
}
extern int rtkpos(rtk_t* rtk, const obsd_t* obs, int n, const nav_t* nav)
{
    prcopt_t* opt = &rtk->opt;
    gtime_t time;
    int i, nu, startup_qc;
    obsd_t clean_obs[MAXOBS];
    char msg[128] = "";

    char tstr[40];
    rtk->ppp_failure=0;
    rtk->ppp_spp_clock_valid=0;
    if(!obs||!nav||n<=0||opt->mode<PMODE_PPP_KINEMA||opt->mode>PMODE_PPP_FIXED){rtk->sol.stat=SOLQ_NONE;return 0;}
    if(n>MAXOBS || opt->nf<1 || opt->nf>NFREQ || rtk->nx!=pppnx(opt) ||
        opt->ppp_baseline<0 || opt->ppp_baseline>MODE_C_NEW_ROBUST) {
        rtk->ppp_failure=FILTER_BAD_DIM;rtk->sol.stat=SOLQ_NONE;
        trace(2,"$PPP_FAILURE,reason=BAD_DIM,stage=ENTRY\n");return 0;
    }
    for(i=0;i<n;i++) {
        int f;
        if(!isfinite(obs[i].time.sec)) {
            rtk->ppp_failure=FILTER_NAN_INPUT;rtk->sol.stat=SOLQ_NONE;
            trace(2,"$PPP_FAILURE,reason=NAN_INPUT,stage=OBS_TIME\n");return 0;
        }
        for(f=0;f<NFREQ+NEXOBS;f++) if(!isfinite(obs[i].P[f]) ||
            !isfinite(obs[i].L[f]) || !isfinite(obs[i].D[f])) {
            rtk->ppp_failure=FILTER_NAN_INPUT;rtk->sol.stat=SOLQ_NONE;
            trace(2,"$PPP_FAILURE,reason=NAN_INPUT,stage=RAW_OBS,sat=%d,F%d\n",obs[i].sat,f+1);
            ppp_input_failure_arcs(rtk,obs,n);
            return 0;
        }
    }
    ppp_innovation_log_epoch(obs[0].time);
    startup_qc=startup_qc_enabled(opt);
    { /* Input bounds protection applies to every baseline. */
        n=sanitize_ppp_input(obs,n,clean_obs);obs=clean_obs;
        if(n<=0){rtk->sol.stat=SOLQ_NONE;return 0;}
        /* The timer must also run with DOPPWARM=0. Do not enable warm-up or
         * set ppp_start_valid (used separately by the existing bias model). */
        if(startup_qc && !rtk->ppp_start_time.time) {
            rtk->ppp_start_time=obs[0].time;
            trace(2,"$START_QC_CONFIG,window_sec=30,spp_min_dof=2,spp_abs_gate_m=30,spp_z_gate=6,spatial_min_peers=5,code_pair_min_peers=4,spatial_MAD_factor=6\n");
        }
    }
    /* Keep RTKLIB frequency slots unchanged (L1/L2/L5). Missing L2 data are
       handled naturally as zero observations; never rewrite const input data. */
    trace(3, "rtkpos  : time=%s n=%d\n", time2str(obs[0].time, tstr, 3), n);
    trace(4, "obs=\n"); traceobs(4, obs, n);
    /*trace(5,"nav=\n"); tracenav(5,nav);*/

    /* Count observations from the rover. */
    for (nu = 0; nu < n && obs[nu].rcv == 1; nu++);

    time = rtk->sol.time; /* previous epoch */

    /* rover position and time by single point positioning, skip if
     position variance smaller than threshold */
    if (rtk->P[0] == 0 || rtk->P[0] > STD_PREC_VAR_THRESH) {
        int isolated=smartphone_ppp_policy(opt).common_clock;
        int bootstrap=!rtk->ppp_clock_valid || norm(rtk->x,3)<=0.0;
        sol_t spp_candidate=rtk->sol;
        sol_t *spp_output=isolated?&spp_candidate:&rtk->sol;
        int startup=startup_qc && (norm(rtk->x,3)<=0.0 ||
            fabs(timediff(obs[0].time,rtk->ppp_start_time))<=30.0);
        int spp_ok=startup ? pntpos_startup(obs,nu,nav,opt,spp_output,NULL,rtk->ssat,msg) :
            pntpos(obs,nu,nav,opt,spp_output,NULL,rtk->ssat,msg);
        if(spp_ok) {
            rtk->ppp_spp_clock_valid=1;rtk->ppp_spp_clock_m=CLIGHT*spp_output->dtr[0];
            if(isolated && bootstrap)rtk->sol=spp_candidate;
        }
        ppp_innovation_note_spp(spp_ok && (!isolated || bootstrap));
        if(isolated) {
            trace(2,"$SPP_CLOCK_CANDIDATE,ok=%d,used_for_bootstrap=%d,clock_m=%.17g,previous_ppp_clock_m=%.17g\n",
                spp_ok,spp_ok&&bootstrap,spp_ok?rtk->ppp_spp_clock_m:0.0,rtk->ppp_clock_m);
            if(!bootstrap)rtk->sol.time=obs[0].time;
        }
        if(startup_qc&&spp_ok&&norm(rtk->x,3)<=0.0&&
            fabs(timediff(obs[0].time,rtk->ppp_start_time))>30.0) {
            /* Clock-only/sparse callbacks before the first usable SPP must
             * not consume the bootstrap window before PPP can initialize. */
            rtk->ppp_start_time=obs[0].time;
            trace(2,"$START_TIMER_RESTART,reason=FIRST_USABLE_SPP\n");
        }
        if(startup && spp_ok)for(i=0;i<nu;i++) {
            int sat=obs[i].sat;
            if(rtk->ssat[sat-1].ppp_code_bad[0]) {
                clean_obs[i].P[0]=0.0;
                rtk->ssat[sat-1].psmvalid[0]=0;
            }
        }
        if (!spp_ok) {
            errmsg(rtk, "point pos error (%s)\n", msg);

            /*
             * A phone may temporarily lose a valid SPP solution even though
             * the PPP/dynamics filter already has a usable state. Do not freeze
             * rtk->tt at zero in that case: advance to the observation time and
             * use the predicted PPP state as the coarse receiver position.
             */
            if (rtk->opt.mode < PMODE_PPP_KINEMA || norm(rtk->x, 3) <= 0.0) {
                outsolstat(rtk, nav);
                return 0;
            }
            /* pntpos commits only accepted solutions. dtr therefore remains
             * from the last trusted solution, never from a rejected SPP. */
            trace(2,"$SPP_FALLBACK,clock_source=PREVIOUS_TRUSTED_SOLUTION,clock_s=%.17g\n",rtk->sol.dtr[0]);
            rtk->sol.time = obs[0].time;
            for (i = 0; i < 3; i++) rtk->sol.rr[i] = rtk->x[i];
            if (rtk->opt.dynamics) {
                for (i = 3; i < 6; i++) rtk->sol.rr[i] = rtk->x[i];
            }
        }
    }
    else rtk->sol.time = obs[0].time;
    if (time.time != 0) rtk->tt = timediff(rtk->sol.time, time);

    /* suppress output of single solution */
    if (!opt->outsingle) {
        rtk->sol.stat = SOLQ_NONE;
    }
    /* precise point positioning */
    if (opt->mode >= PMODE_PPP_KINEMA) {
        pppos(rtk, obs, nu, nav);
        /* Corrected lifecycle keeps valid carrier history at every epoch.
         * LEGACY deliberately reproduces the old 30-second startup window. */
        if(startup_qc || smartphone_ppp_policy(opt).corrected_lifecycle)for(i=0;i<nu&&i<MAXOBS;i++) {
            int f,sat=obs[i].sat;
            for(f=0;f<opt->nf&&f<NFREQ;f++) {
                if(smartphone_ppp_policy(opt).corrected_lifecycle ||
                    fabs(timediff(obs[i].time,rtk->ppp_start_time))<=30.0) {
                    if(!smartphone_ppp_policy(opt).corrected_lifecycle ||
                        (obs[i].L[f]!=0.0 && isfinite(obs[i].L[f]))) {
                        rtk->ssat[sat-1].pt[0][f]=obs[i].time;
                        rtk->ssat[sat-1].ph[0][f]=obs[i].L[f];
                    }
                }
                else {
                    rtk->ssat[sat-1].pt[0][f].time=0;
                    rtk->ssat[sat-1].pt[0][f].sec=0.0;
                    rtk->ssat[sat-1].ph[0][f]=0.0;
                }
            }
        }
        outsolstat(rtk, nav);
        return rtk->ppp_failure ? 0 : 1;
    }
    return 0;
}
