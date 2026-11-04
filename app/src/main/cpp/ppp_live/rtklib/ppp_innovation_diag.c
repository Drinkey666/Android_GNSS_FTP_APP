#include "ppp_innovation_diag.h"
#include "smartphone_ppp_config.h"
#include <stdio.h>
#include <stdlib.h>
#include <math.h>

int ppp_innovation_diag(const double *x,const double *P,const double *v,
    const double *H,const double *R,int nx,int nv,
    ppp_innovation_value_t *out,double *full_S)
{
    int i,j,a,b,k,total=0,*index,*offset,goodP;
    if(nx<=0 || nv<=0 || !x || !P || !v || !H || !R || !out ||
        (size_t)nx>(size_t)-1/sizeof(int)/(size_t)nv)return -1;
    for(i=0;i<nv;i++) {
        out[i].v=v[i];out[i].obs_var=R[i+i*nv];
        out[i].pred_var=out[i].Sii=out[i].sqrt_Sii=out[i].u=NAN;
        out[i].u_valid=out[i].inactive_state_support=0;
        out[i].invalid_reason=(!isfinite(v[i])?INNOV_BAD_V:0)|
            (!isfinite(R[i+i*nv])?INNOV_BAD_R:0);
    }
    if(full_S)for(i=0;i<nv;i++)for(j=0;j<nv;j++)full_S[i+j*nv]=NAN;
    index=(int *)malloc((size_t)nx*nv*sizeof(int));
    offset=(int *)malloc(((size_t)nv+1)*sizeof(int));
    if(!index || !offset) {
        for(i=0;i<nv;i++)out[i].invalid_reason|=INNOV_NO_MEMORY;
        free(index);free(offset);return 0;
    }
    goodP=numeric_finite(P,(size_t)nx*nx);
    for(i=0;i<nv;i++) {
        offset[i]=total;
        if(!goodP)out[i].invalid_reason|=INNOV_BAD_P;
        for(a=0;a<nx;a++) {
            double h=H[a+i*nx];
            if(!isfinite(h))out[i].invalid_reason|=INNOV_BAD_H;
            if(h==0.0)continue;
            index[total++]=a;
            /* Expose the RTKLIB zero-state sentinel without changing S. */
            if(x[a]==0.0 || !(P[a+a*nx]>0.0)) {
                for(k=0;k<nx;k++)if(P[a+k*nx]!=0.0 || P[k+a*nx]!=0.0) {
                    out[i].inactive_state_support=1;break;
                }
            }
        }
    }
    offset[nv]=total;
    for(i=0;i<nv;i++) {
        double pred=0.0;
        if(!(out[i].invalid_reason&(INNOV_BAD_P|INNOV_BAD_H))) {
            /* Exact sparse form of h_i P h_i'; cross covariance retained. */
            for(a=offset[i];a<offset[i+1];a++)for(b=offset[i];b<offset[i+1];b++)
                pred+=H[index[a]+i*nx]*P[index[a]+index[b]*nx]*H[index[b]+i*nx];
            out[i].pred_var=pred;out[i].Sii=pred+out[i].obs_var;
        }
        if(!isfinite(out[i].Sii))out[i].invalid_reason|=INNOV_BAD_S;
        else if(out[i].Sii<=0.0)out[i].invalid_reason|=INNOV_NONPOSITIVE_S;
        else {
            out[i].sqrt_Sii=sqrt(out[i].Sii);
            if(isfinite(v[i]))out[i].u=v[i]/out[i].sqrt_Sii;
            if(!isfinite(out[i].u))out[i].invalid_reason|=INNOV_BAD_U;
        }
        out[i].u_valid=out[i].invalid_reason==0;
        if(full_S) {
            for(j=0;j<nv;j++) {
                double s=0.0;
                if(!goodP || (out[i].invalid_reason&INNOV_BAD_H) ||
                    (out[j].invalid_reason&INNOV_BAD_H))continue;
                for(a=offset[i];a<offset[i+1];a++)for(b=offset[j];b<offset[j+1];b++)
                    s+=H[index[a]+i*nx]*P[index[a]+index[b]*nx]*H[index[b]+j*nx];
                full_S[i+j*nv]=s+R[i+j*nv];
            }
        }
    }
    free(index);free(offset);return 0;
}

static FILE *innovation_csv;
static gtime_t first_input,current_input;
static unsigned long epoch_index,distinct_epoch;
static int io_error;
typedef struct {
    gtime_t last,start;
    unsigned long last_epoch,id,count,broken_epoch;
    int code,seen,ever_ambiguity,pending_before,bits,gap,switched,ambiguity_init;
} innovation_track_t;
static innovation_track_t innovation_track[MAXSAT][NFREQ];
static innovation_track_t measurement_track[MAXSAT][NFREQ][2];
static unsigned char *init_state;
static int init_nx,core_provenance_ok,spp_current,spp_attempted,current_init,current_adjustment,same_input;
static void provenance_reset(void)
{
    memset(innovation_track,0,sizeof(innovation_track));
    memset(measurement_track,0,sizeof(measurement_track));distinct_epoch=0;
    free(init_state);init_state=NULL;init_nx=0;core_provenance_ok=0;
    spp_current=spp_attempted=current_init=current_adjustment=same_input=0;
}
static void break_track(innovation_track_t *t,gtime_t time,int bits)
{
    if(t->broken_epoch!=distinct_epoch) {
        t->id++;t->count=1;t->start=time;t->broken_epoch=distinct_epoch;
    }
    t->bits&=~INNOV_LIFE_CONTINUOUS;t->bits|=bits;
}
void ppp_innovation_note_spp(int success)
{
    if(ppp_innovation_log_enabled()){spp_attempted=1;spp_current=success!=0;}
}
int ppp_innovation_spp_current(void){return ppp_innovation_log_enabled() && spp_current;}
void ppp_innovation_core_begin(int nx,const obsd_t *obs,int n,int nf,const rtk_t *rtk)
{
    int i,f,sat;
    if(!ppp_innovation_log_enabled())return;
    current_init=current_adjustment=0;
    if(init_nx!=nx) {free(init_state);init_state=(unsigned char *)calloc(nx,1);init_nx=nx;}
    else if(init_state)memset(init_state,0,nx);
    core_provenance_ok=init_state!=NULL && !(spp_attempted && !spp_current);
    for(i=0;i<MAXSAT;i++)for(f=0;f<NFREQ;f++) {
        innovation_track[i][f].bits=innovation_track[i][f].gap=innovation_track[i][f].switched=0;
        innovation_track[i][f].ambiguity_init=0;
    }
    for(i=0;i<n;i++) {
        sat=obs[i].sat;if(sat<1||sat>MAXSAT)continue;
        for(f=0;f<nf && f<NFREQ;f++) {
            innovation_track_t *t=&innovation_track[sat-1][f];
            double dt;
            if(!obs[i].code[f] || (obs[i].P[f]==0.0 && obs[i].L[f]==0.0))continue;
            dt=timediff(obs[i].time,t->last);
            t->pending_before=rtk->ssat[sat-1].ppp_arc_pending[f]!=0;
            if(!t->seen)break_track(t,obs[i].time,INNOV_LIFE_INIT);
            else if(t->code!=obs[i].code[f]) {
                t->switched=1;break_track(t,obs[i].time,INNOV_LIFE_RESTART);
            }
            else if(!(same_input && dt==0.0) &&
                (t->last_epoch+1!=epoch_index || dt<=0.0 || dt>1.5)) {
                t->gap=1;break_track(t,obs[i].time,INNOV_LIFE_RECOVERY|INNOV_LIFE_POST_GAP);
            }
            else {t->bits=INNOV_LIFE_CONTINUOUS;if(!same_input)t->count++;}
            if(obs[i].LLI[f]&LLI_SLIP)break_track(t,obs[i].time,INNOV_LIFE_RESTART);
            t->seen=1;t->code=obs[i].code[f];t->last=obs[i].time;t->last_epoch=epoch_index;
        }
    }
}
void ppp_innovation_note_current_init(int state,int sat,int slot,int ambiguity)
{
    innovation_track_t *t;
    if(!ppp_innovation_log_enabled())return;
    current_init=1;if(init_state && state>=0 && state<init_nx)init_state[state]=1;
    if(!ambiguity || sat<1 || sat>MAXSAT || slot<0 || slot>=NFREQ)return;
    t=&innovation_track[sat-1][slot];t->ambiguity_init=1;
    break_track(t,t->last,t->ever_ambiguity?INNOV_LIFE_RESTART:INNOV_LIFE_INIT);
    if(t->pending_before)t->bits|=INNOV_LIFE_RECOVERY;
    t->ever_ambiguity=1;
}
void ppp_innovation_note_current_adjustment(int state)
{
    if(!ppp_innovation_log_enabled())return;
    current_adjustment=1;
    /* Bit 2 separates current-observation adjustments from initialization. */
    if(init_state && state>=0 && state<init_nx)init_state[state]|=2;
}
void ppp_innovation_core_events(const obsd_t *obs,int n,const rtk_t *rtk,
    const unsigned char *observed_slip)
{
    int i,f,sat;
    if(!ppp_innovation_log_enabled())return;
    for(i=0;i<n;i++) {
        sat=obs[i].sat;if(sat<1 || sat>MAXSAT)continue;
        for(f=0;f<rtk->opt.nf && f<NFREQ;f++) {
            innovation_track_t *t=&innovation_track[sat-1][f];
            const ssat_t *ss=&rtk->ssat[sat-1];
            if(t->last_epoch!=epoch_index)continue;
            if((observed_slip && observed_slip[i*NFREQ+f]) || (ss->slip[f]&LLI_SLIP) || ss->ppp_arc_reset[f])
                break_track(t,obs[i].time,INNOV_LIFE_RESTART);
            if(ss->ppp_slip_evidence[f]&PPP_EVIDENCE_GAP) {
                t->gap=1;break_track(t,obs[i].time,INNOV_LIFE_POST_GAP|INNOV_LIFE_RECOVERY);
            }
        }
    }
}
void ppp_innovation_meta_provenance(ppp_innovation_meta_t *m,const double *H,int nx,int trial)
{
    int a;
    if(!ppp_innovation_log_enabled())return;
    m->state_init_from_current_obs=current_init;m->spp_from_current_obs=spp_current;
    m->spp_attempted=spp_attempted;m->provenance_valid=core_provenance_ok;
    m->legacy_current_obs_adjustment=current_adjustment;m->same_epoch_reentry=same_input || trial>0;
    if(H && init_state)for(a=0;a<nx && a<init_nx;a++)
        if(H[a+m->row_index*nx]!=0.0 && (init_state[a]&1))m->state_init_affects_row=1;
    if(m->source_obs_index>=0 && m->sat>0 && m->sat<=MAXSAT && m->frequency_slot>=0 && m->frequency_slot<NFREQ) {
        const innovation_track_t *t=&innovation_track[m->sat-1][m->frequency_slot];
        m->ambiguity_init_from_current_obs=t->ambiguity_init;m->arc_id=t->id;
        m->continuous_epochs=t->count;m->arc_age=timediff(m->time,t->start);
        m->lifecycle_bits=t->bits;m->gap_flag=t->gap;m->signal_switch_flag=t->switched;
        m->raw_arc_id=t->id;m->raw_arc_age=m->arc_age;
        m->raw_continuous_epochs=t->count;m->raw_lifecycle_bits=t->bits;
        /* Track accepted pre-fit rows separately for P and L. Missing a row
         * breaks only that observable's sequence; no edit trial advances it. */
        if(m->measurement_class==INNOV_GNSS_CODE || m->measurement_class==INNOV_GNSS_PHASE) {
            int type=m->measurement_class==INNOV_GNSS_CODE?0:1;
            innovation_track_t *q=&measurement_track[m->sat-1][m->frequency_slot][type];
            if(trial==0 && !(same_input && q->seen && timediff(m->time,q->last)==0.0)) {
                double dt=timediff(m->time,q->last);
                int bits=t->bits&~INNOV_LIFE_CONTINUOUS;
                q->gap=t->gap;q->switched=t->switched;
                q->bits=0;
                if(!q->seen)break_track(q,m->time,bits|INNOV_LIFE_INIT);
                else if(q->code!=m->signal || bits)break_track(q,m->time,bits?bits:INNOV_LIFE_RESTART);
                else if(q->last_epoch+1!=epoch_index || dt<=0.0 || dt>1.5) {
                    q->gap=1;break_track(q,m->time,INNOV_LIFE_RECOVERY|INNOV_LIFE_POST_GAP);
                }
                else {q->bits=INNOV_LIFE_CONTINUOUS;q->count++;}
                q->seen=1;q->code=m->signal;q->last=m->time;q->last_epoch=epoch_index;
            }
            else if(trial==0 && same_input)q->last_epoch=epoch_index;
            m->arc_id=q->id;m->arc_age=timediff(m->time,q->start);m->continuous_epochs=q->count;
            m->lifecycle_bits=q->bits;m->gap_flag=q->gap;m->signal_switch_flag=q->switched;
        }
    }
}
const char *ppp_innovation_class_name(ppp_measurement_class_t c)
{
    switch(c) {
        case INNOV_GNSS_CODE:return "GNSS_CODE";
        case INNOV_GNSS_PHASE:return "GNSS_PHASE";
        case INNOV_IONO_PRIOR:return "IONO_PRIOR";
        case INNOV_TROP_PRIOR:return "TROP_PRIOR";
        default:return "OTHER_CONSTRAINT";
    }
}
static const char *arc_name(int a)
{
    switch(a) {
        case INNOV_ARC_ACTIVE:return "ACTIVE";
        case INNOV_ARC_PENDING:return "ARC_PENDING_INIT";
        case INNOV_ARC_REINITIALIZED:return "ARC_REINITIALIZED";
        case INNOV_ARC_UNINITIALIZED:return "UNINITIALIZED";
        case INNOV_ARC_LEGACY_ACTIVE:return "LEGACY_ACTIVE";
        default:return "NA";
    }
}
static void reasons(int reason,char *s)
{
    s[0]=0;
#define REASON(bit,name) if(reason&(bit)){if(*s)strcat(s,"|");strcat(s,name);}
    REASON(INNOV_BAD_V,"NONFINITE_V");REASON(INNOV_BAD_P,"NONFINITE_P");
    REASON(INNOV_BAD_H,"NONFINITE_H");REASON(INNOV_BAD_R,"NONFINITE_R");
    REASON(INNOV_BAD_S,"NONFINITE_S");REASON(INNOV_NONPOSITIVE_S,"NONPOSITIVE_S");
    REASON(INNOV_BAD_U,"NONFINITE_U");REASON(INNOV_NO_MEMORY,"DIAG_NO_MEMORY");
#undef REASON
    if(!*s)strcpy(s,"OK");
}
int ppp_innovation_log_open(const char *path)
{
    ppp_innovation_log_close();provenance_reset();io_error=0;epoch_index=0;
    first_input.time=current_input.time=0;first_input.sec=current_input.sec=0.0;
    if(!path || !*path)return 1;
    innovation_csv=fopen(path,"w");
    if(!innovation_csv)return 0;
    fprintf(innovation_csv,"time,week,tow,epoch,elapsed_s,stage,trial,sample_scope,baseline,lifecycle,row,sat,sys,signal,signal2,frequency_slot,frequency_hz,frequency2_hz,combined,obs_type,elevation_deg,cn0_dbhz,source_obs_index,v,R0_or_R,R0,pred_var,obs_var,Sii,sqrt_Sii,u,finite_flag,invalid_reason,measurement_class,slip_flag,arc_status,legacy_reject_flag,inactive_state_support,epoch_failure,solution_status,posterior_committed,abs_v,sqrt_Rii,sqrt_pred_var,abs_u,lifecycle_tags,state_init_from_current_obs,state_init_affects_row,ambiguity_init_from_current_obs,same_epoch_reentry,arc_id,arc_age,continuous_epochs,gap_flag,signal_switch_flag,spp_from_current_obs,spp_attempted,legacy_current_obs_adjustment,provenance_valid,clock_m,clock_var,isb_m,isb_var,iono_m,iono_var,trop_m,trop_var,ambiguity_m,observer_learning_label,raw_arc_id,raw_arc_age,raw_continuous_epochs,raw_lifecycle_bits\n");
    return !ferror(innovation_csv);
}
int ppp_innovation_log_close(void)
{
    if(innovation_csv){if(fclose(innovation_csv))io_error=1;innovation_csv=NULL;}
    return !io_error;
}
int ppp_innovation_log_enabled(void){return innovation_csv!=NULL && !io_error;}
void ppp_innovation_log_epoch(gtime_t time)
{
    if(!ppp_innovation_log_enabled())return;
    same_input=epoch_index>0 && timediff(time,current_input)==0.0;
    if(!same_input)distinct_epoch++;
    spp_current=spp_attempted=0;
    current_input=time;
    if(!epoch_index)first_input=time;
    epoch_index++;
}
void ppp_innovation_log_trial(int trial,int baseline,int corrected,
    const ppp_innovation_meta_t *meta,const ppp_innovation_value_t *value,
    int count,const unsigned char *final_mexc,int mexc_count,
    int epoch_failure,int solution_status,int posterior_committed)
{
    int i,week;char time[40],sid[8],reason[200],life[160];
    double elapsed;
    if(!ppp_innovation_log_enabled() || !meta || !value)return;
    elapsed=timediff(current_input,first_input);
    for(i=0;i<count;i++) {
        const ppp_innovation_meta_t *m=&meta[i];const ppp_innovation_value_t *d=&value[i];
        const char *sys=m->sys==SYS_GPS?"G":m->sys==SYS_GAL?"E":m->sys==SYS_CMP?"C":
            m->sys==SYS_GLO?"R":m->sys==SYS_QZS?"J":m->sys==SYS_SBS?"S":m->sys==SYS_IRN?"I":"";
        const char *type=m->measurement_class==INNOV_GNSS_CODE?"P":m->measurement_class==INNOV_GNSS_PHASE?"L":"PRIOR";
        int rejected=m->mexc_index>=0 && m->mexc_index<mexc_count && final_mexc && final_mexc[m->mexc_index];
        double tow=time2gpst(m->time,&week);
        sid[0]=0;if(m->sat)satno2id(m->sat,sid);time2str(m->time,time,3);reasons(d->invalid_reason,reason);
        strcpy(life,elapsed>=0 && elapsed<30.0?"STARTUP":"STABLE");
#define LIFE(bit,name) if(m->lifecycle_bits&(bit)){strcat(life,"|");strcat(life,name);}
        LIFE(INNOV_LIFE_INIT,"ARC_INIT");LIFE(INNOV_LIFE_RESTART,"ARC_RESTART");
        LIFE(INNOV_LIFE_RECOVERY,"ARC_RECOVERY");LIFE(INNOV_LIFE_POST_GAP,"POST_GAP");
        LIFE(INNOV_LIFE_CONTINUOUS,"NORMAL_CONTINUOUS");
#undef LIFE
        if(m->same_epoch_reentry)strcat(life,"|EDIT_RETRY");
        fprintf(innovation_csv,"%s,%d,%.9f,%lu,%.9f,%s,%d,%s,%s,%s,%d,%s,%s,%s,%s,%d,%.17g,%.17g,%d,%s,%.9f,%.9f,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%s,%s,%d,%s,%d,%d,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%s,%d,%d,%d,%d,%lu,%.17g,%lu,%d,%d,%d,%d,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%lu,%.17g,%lu,%d\n",
            time,week,tow,epoch_index,elapsed,elapsed>=0 && elapsed<30.0?"STARTUP":"STEADY",trial,
            trial==0?"FIRST_PREFIT":"EDIT_RETRY",
            baseline==PPP_BASELINE_A_CLEAN?"A":baseline==MODE_C_NEW_ROBUST?"C":"B",
            corrected?"CORRECTED":"LEGACY",
            m->row_index,sid,sys,m->signal?code2obs(m->signal):"",m->signal2?code2obs(m->signal2):"",
            m->frequency_slot,m->frequency_hz,m->frequency2_hz,m->combined,type,m->elevation_deg,m->cn0_dbhz,
            m->source_obs_index,d->v,d->obs_var,m->r0,d->pred_var,d->obs_var,d->Sii,d->sqrt_Sii,d->u,
            d->u_valid,reason,ppp_innovation_class_name(m->measurement_class),m->slip_flag,arc_name(m->arc_status),
            rejected,d->inactive_state_support,epoch_failure,solution_status,posterior_committed,fabs(d->v),
            isfinite(d->obs_var)&&d->obs_var>=0?sqrt(d->obs_var):NAN,
            isfinite(d->pred_var)&&d->pred_var>=0?sqrt(d->pred_var):NAN,fabs(d->u),life,m->state_init_from_current_obs,m->state_init_affects_row,
            m->ambiguity_init_from_current_obs,m->same_epoch_reentry,m->arc_id,m->arc_age,m->continuous_epochs,
            m->gap_flag,m->signal_switch_flag,m->spp_from_current_obs,m->spp_attempted,
            m->legacy_current_obs_adjustment,m->provenance_valid,m->clock_m,m->clock_var,m->isb_m,m->isb_var,
            m->iono_m,m->iono_var,m->trop_m,m->trop_var,m->ambiguity_m,
            m->state_init_from_current_obs?"NOT_ELIGIBLE_FOR_R_LEARNING":"UNASSESSED",
            m->raw_arc_id,m->raw_arc_age,m->raw_continuous_epochs,m->raw_lifecycle_bits);
    }
    if(ferror(innovation_csv)) {io_error=1;fprintf(stderr,"INNOV_DIAG_IO_ERROR,estimator_unchanged=1\n");}
}
