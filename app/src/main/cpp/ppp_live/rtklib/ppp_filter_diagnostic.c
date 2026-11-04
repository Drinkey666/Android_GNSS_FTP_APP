/* This translation unit has no diagnostic symbols when the macro is absent. */
#ifdef ENABLE_FILTER_DIAGNOSTIC
#include "ppp_filter_diagnostic.h"
#include "smartphone_ppp_config.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static FILE *fd_file;
static const rtk_t *fd_owner;
static int fd_epoch=-1,fd_trial=-1,fd_iteration,fd_call,fd_in_filter;
static int fd_np,fd_nc,fd_nt,fd_ni,fd_nd,fd_nf,fd_nx,fd_open_attempted,fd_scope;
static gtime_t fd_time;
static uint8_t fd_signals[MAXSAT][NFREQ];
static const char *fd_profile;
static const char *fd_baseline;

static void fd_number(double value)
{ if(isfinite(value))fprintf(fd_file,"%.17g",value);else fputs("null",fd_file); }
static void fd_array(const double *a,size_t n)
{
    size_t i;fputc('[',fd_file);
    for(i=0;i<n;i++){if(i)fputc(',',fd_file);fd_number(a[i]);}
    fputc(']',fd_file);
}
static void fd_header(const char *event)
{
    char ts[40];time2str(fd_time,ts,3);
    fprintf(fd_file,"{\"schema\":1,\"path\":\"%s_%s\",\"time\":\"%s\",\"epoch\":%d,\"trial\":%d,\"iteration\":%d,\"call\":%d,\"event\":\"%s\"",
        fd_profile,fd_baseline,ts,fd_epoch,fd_trial,fd_iteration,fd_call,event);
}
static void fd_finish(void)
{
    fputs("}\n",fd_file);
    if(fflush(fd_file) || ferror(fd_file)){
        fclose(fd_file);fd_file=NULL;fd_in_filter=0;
        trace(2,"$FILTER_DIAGNOSTIC_FAILURE,reason=IO_ERROR,estimator_unchanged=1\n");
    }
}
int ppp_fd_enabled(void){return fd_file && fd_owner && fd_scope && fd_epoch>=0 && fd_epoch<6;}

static void fd_identity(int index)
{
    int a=fd_np,b=a+fd_nc,c=b+fd_nt,d=c+fd_ni,e=d+fd_nd;
    const char *group="OTHER",*meaning="OTHER";int sat=0,slot=-1;char id[8]="";
    if(index<a){group="POSITION";meaning=index<3?"ECEF_M":"DYNAMIC_STATE";}
    else if(index<b){group="CLOCK";meaning=index==a?"COMMON_CLOCK_M":"ISB_M";}
    else if(index<c){group=index==b?"ZTD":"GRADIENT";
        meaning=index==b?(fd_owner->opt.ppp_wet_correction?"WET_CORRECTION_M":"TOTAL_ZTD_M"):"TROP_GRADIENT_M";}
    else if(index<d){group="IONO";sat=index-c+1;
        meaning=fd_owner->opt.ppp_rcb_datum?"EFFECTIVE_VERTICAL_L1_M":"PHYSICAL_VERTICAL_L1_M";}
    else if(index<e){group="RCB";meaning="RELATIVE_CODE_BIAS_M";}
    else {group="AMBIGUITY";sat=(index-e)%MAXSAT+1;slot=(index-e)/MAXSAT;
        meaning=fd_owner->opt.ionoopt==IONOOPT_IFLC?"IFLC_AMBIGUITY_M":"SIGNAL_AMBIGUITY_M";}
    if(sat)satno2id(sat,id);
    fprintf(fd_file,"{\"index\":%d,\"group\":\"%s\",\"meaning\":\"%s\",\"sat\":\"%s\",\"slot\":%d,\"signal\":\"%s\"}",
        index,group,meaning,id,slot,sat && slot>=0 && slot<NFREQ?code2obs(fd_signals[sat-1][slot]):"");
}
void ppp_fd_epoch(const rtk_t *rtk,const obsd_t *obs,int n,
    int np,int nc,int nt,int ni,int nd,int nf)
{
    int i,j;const char *path;
    if(!fd_open_attempted){
        fd_open_attempted=1;path=getenv("RTK_PPP_FILTER_DIAGNOSTIC");
        if(path && *path){
            fd_file=fopen(path,"wb");
            if(!fd_file)trace(2,"$FILTER_DIAGNOSTIC_FAILURE,reason=OPEN_FAILED,estimator_unchanged=1\n");
        }
    }
    if(!fd_file)return;
    if(fd_owner && fd_owner!=rtk){
        trace(2,"$FILTER_DIAGNOSTIC_FAILURE,reason=MULTIPLE_SESSIONS_UNSUPPORTED,estimator_unchanged=1\n");
        fclose(fd_file);fd_file=NULL;return;
    }
    fd_owner=rtk;fd_scope=1;fd_epoch++;fd_time=obs[0].time;fd_trial=-1;fd_iteration=fd_call=fd_in_filter=0;
    fd_np=np;fd_nc=nc;fd_nt=nt;fd_ni=ni;fd_nd=nd;fd_nf=nf;fd_nx=rtk->nx;
    fd_profile=rtk->opt.ppp_physical_atmosphere?"PHYSICAL":rtk->opt.ppp_rcb_datum?"PAIR_DATUM":"LEGACY";
    fd_baseline=rtk->opt.ppp_baseline==PPP_BASELINE_A_CLEAN?"A":rtk->opt.ppp_baseline==PPP_BASELINE_B_LEGACY?"B":"C";
    if(!ppp_fd_enabled())return;
    memset(fd_signals,0,sizeof(fd_signals));
    for(i=0;i<n && i<MAXOBS;i++)if(obs[i].sat>=1 && obs[i].sat<=MAXSAT)
        for(j=0;j<NFREQ;j++)fd_signals[obs[i].sat-1][j]=obs[i].code[j];
    fd_header("EPOCH");
    fprintf(fd_file,",\"nx\":%d,\"np\":%d,\"nc\":%d,\"nt\":%d,\"ni\":%d,\"nd\":%d,\"nf\":%d,\"dt_s\":",fd_nx,np,nc,nt,ni,nd,nf);
    fd_number(rtk->tt);
    fprintf(fd_file,",\"allow_zero\":%d,\"iekf\":%d,\"wet_correction\":%d,\"rcb_datum\":%d,\"prn\":",
        !!(rtk->opt.ppp_wet_correction || rtk->opt.ppp_rcb_datum || rtk->opt.ppp_physical_atmosphere),rtk->opt.ppp_iekf,rtk->opt.ppp_wet_correction,rtk->opt.ppp_rcb_datum);
    fd_array(rtk->opt.prn,6);fputs(",\"states\":[",fd_file);
    for(i=0;i<fd_nx;i++){if(i)fputc(',',fd_file);fd_identity(i);}fputc(']',fd_file);fd_finish();
}
void ppp_fd_state(const char *stage,const double *x,const double *P,double ztd,double zwd,double zhd)
{
    int i;if(!ppp_fd_enabled())return;
    fd_header(stage);fputs(",\"x\":",fd_file);fd_array(x,fd_nx);
    fputs(",\"P_diagonal\":[",fd_file);
    for(i=0;i<fd_nx;i++){if(i)fputc(',',fd_file);fd_number(P[i+i*fd_nx]);}fputc(']',fd_file);
    fputs(",\"total_ZTD_m\":",fd_file);fd_number(ztd);
    fputs(",\"model_ZWD_m\":",fd_file);fd_number(zwd);
    fputs(",\"model_ZHD_m\":",fd_file);fd_number(zhd);fd_finish();
}
void ppp_fd_trial(int trial,const ppp_innovation_meta_t *rows,int nv,
    const double *x,const double *P,double ztd,double zwd,double zhd)
{
    int i;if(!ppp_fd_enabled())return;fd_trial=trial;fd_iteration=0;
    ppp_fd_state("FILTER_INPUT",x,P,ztd,zwd,zhd);
    if(!ppp_fd_enabled())return;fd_header("ROWS");fprintf(fd_file,",\"nv\":%d,\"rows\":[",nv);
    for(i=0;i<nv;i++){
        const ppp_innovation_meta_t *m=rows?rows+i:NULL;char id[8]="";
        if(i)fputc(',',fd_file);if(m && m->sat)satno2id(m->sat,id);
        fprintf(fd_file,"{\"row\":%d,\"class\":\"%s\",\"sat\":\"%s\",\"signal\":\"%s\",\"slot\":%d,\"source\":%d}",
            i,m?ppp_innovation_class_name(m->measurement_class):"UNAVAILABLE",id,m?code2obs(m->signal):"",m?m->frequency_slot:-1,m?m->source_obs_index:-1);
    }
    fputc(']',fd_file);fd_finish();
}
void ppp_fd_iteration(int iteration,const double *at,const double *raw,
    const double *adjusted,int nx,int nv)
{
    if(!ppp_fd_enabled())return;fd_iteration=iteration;fd_header("IEKF_LINEARIZATION");
    fputs(",\"linearization_x\":",fd_file);fd_array(at,nx);
    fputs(",\"raw_innovation\":",fd_file);fd_array(raw,nv);
    fputs(",\"filter_innovation\":",fd_file);fd_array(adjusted,nv);fd_finish();
}
void ppp_fd_filter_begin(const double *x,const double *P,const double *H,
    const double *v,const double *R,int n,int m,const int *ix,int k,int allow_zero)
{
    int i;if(!ppp_fd_enabled())return;fd_call++;fd_in_filter=1;
    fd_header("FILTER_CALL");fprintf(fd_file,",\"n_active\":%d,\"nv\":%d,\"nx\":%d,\"allow_zero\":%d,\"layout\":\"COLUMN_MAJOR\",\"active_indices\":[",k,m,n,allow_zero);
    for(i=0;i<k;i++)fprintf(fd_file,"%s%d",i?",":"",ix[i]);fputc(']',fd_file);fd_finish();
    ppp_fd_matrix("X_MINUS_ACTIVE",x,k,1);ppp_fd_matrix("P_MINUS_ACTIVE",P,k,k);
    ppp_fd_matrix("H_ACTIVE",H,k,m);ppp_fd_matrix("V",v,m,1);ppp_fd_matrix("R",R,m,m);
}
void ppp_fd_matrix(const char *name,const double *a,int nr,int nc)
{
    if(!ppp_fd_enabled() || !fd_in_filter)return;
    fd_header(name);fprintf(fd_file,",\"nr\":%d,\"nc\":%d,\"layout\":\"COLUMN_MAJOR\",\"data\":",nr,nc);
    fd_array(a,(size_t)nr*nc);fd_finish();
}
void ppp_fd_filter_end(int info,const double *x,const double *P,int n)
{
    if(!ppp_fd_enabled() || !fd_in_filter)return;
    if(!info){ppp_fd_matrix("X_PLUS_ACTIVE",x,n,1);ppp_fd_matrix("P_PLUS_ACTIVE",P,n,n);}
    if(ppp_fd_enabled()){fd_header("FILTER_RETURN");fprintf(fd_file,",\"info\":%d",info);fd_finish();}
    fd_in_filter=0;
}
void ppp_fd_trial_result(int info,const double *x,const double *P,double ztd,double zwd,double zhd)
{
    if(!ppp_fd_enabled())return;
    if(!info)ppp_fd_state("TRIAL_POSTERIOR",x,P,ztd,zwd,zhd);
    if(ppp_fd_enabled()){fd_header("TRIAL_RETURN");fprintf(fd_file,",\"info\":%d",info);fd_finish();}
}
void ppp_fd_commit(int trial,const double *x,const double *P,double ztd,double zwd,double zhd)
{if(ppp_fd_enabled()){fd_trial=trial;ppp_fd_state("COMMITTED",x,P,ztd,zwd,zhd);}}
void ppp_fd_end(int failure,int solution_status)
{
    if(ppp_fd_enabled()){
        fd_header("EPOCH_END");fprintf(fd_file,",\"failure\":%d,\"solution_status\":%d",failure,solution_status);fd_finish();
    }
    fd_in_filter=0;fd_scope=0;fd_trial=-1;
    if(fd_file && fd_epoch==5){fclose(fd_file);fd_file=NULL;}
}
#endif
