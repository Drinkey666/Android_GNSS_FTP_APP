/* Compile with ENABLE_FILTER_DIAGNOSTIC in every relevant translation unit.
 * Observer-only, one PPP session per PC process. No estimator writes. */
#ifndef PPP_FILTER_DIAGNOSTIC_H
#define PPP_FILTER_DIAGNOSTIC_H
#ifdef ENABLE_FILTER_DIAGNOSTIC
#include "ppp_innovation_diag.h"
void ppp_fd_epoch(const rtk_t *rtk,const obsd_t *obs,int n,
    int np,int nc,int nt,int ni,int nd,int nf);
void ppp_fd_state(const char *stage,const double *x,const double *P,
    double total_ztd,double model_zwd,double model_zhd);
void ppp_fd_trial(int trial,const ppp_innovation_meta_t *rows,int nv,
    const double *x,const double *P,double ztd,double zwd,double zhd);
void ppp_fd_iteration(int iteration,const double *at,const double *raw,
    const double *adjusted,int nx,int nv);
void ppp_fd_filter_begin(const double *x,const double *P,const double *H,
    const double *v,const double *R,int n,int m,const int *ix,int k,int allow_zero);
void ppp_fd_matrix(const char *name,const double *a,int nr,int nc);
void ppp_fd_filter_end(int info,const double *x,const double *P,int n);
void ppp_fd_trial_result(int info,const double *x,const double *P,
    double ztd,double zwd,double zhd);
void ppp_fd_commit(int trial,const double *x,const double *P,double ztd,double zwd,double zhd);
void ppp_fd_end(int failure,int solution_status);
int ppp_fd_enabled(void);
#endif
#endif
