/* Phase 2: observational diagnostics only. No estimator decisions or writes. */
#ifndef PPP_INNOVATION_DIAG_H
#define PPP_INNOVATION_DIAG_H
#include "rtklib.h"
#ifdef __cplusplus
extern "C" {
#endif
typedef enum {
    INNOV_OTHER_CONSTRAINT=0, INNOV_GNSS_CODE, INNOV_GNSS_PHASE,
    INNOV_IONO_PRIOR, INNOV_TROP_PRIOR
} ppp_measurement_class_t;
typedef struct {
    gtime_t time;
    int row_index,source_obs_index,sat,sys,frequency_slot,mexc_index;
    uint8_t signal,signal2;
    double frequency_hz,frequency2_hz,elevation_deg,cn0_dbhz,r0;
    ppp_measurement_class_t measurement_class;
    int slip_flag,arc_status,combined;
    /* Phase 2.5 provenance: observer-owned, never estimator state. */
    int state_init_from_current_obs,state_init_affects_row;
    int ambiguity_init_from_current_obs,same_epoch_reentry;
    unsigned long arc_id,continuous_epochs;
    double arc_age;
    unsigned long raw_arc_id,raw_continuous_epochs;
    double raw_arc_age;
    int raw_lifecycle_bits;
    int lifecycle_bits,gap_flag,signal_switch_flag,spp_from_current_obs;
    int legacy_current_obs_adjustment,provenance_valid,spp_attempted;
    double clock_m,clock_var,isb_m,isb_var,iono_m,iono_var,trop_m,trop_var,ambiguity_m;
} ppp_innovation_meta_t;
enum { INNOV_LIFE_INIT=1,INNOV_LIFE_RESTART=2,INNOV_LIFE_RECOVERY=4,
    INNOV_LIFE_POST_GAP=8,INNOV_LIFE_CONTINUOUS=16 };
enum { INNOV_ARC_NA=0,INNOV_ARC_ACTIVE,INNOV_ARC_PENDING,
    INNOV_ARC_REINITIALIZED,INNOV_ARC_UNINITIALIZED,INNOV_ARC_LEGACY_ACTIVE };
enum { INNOV_BAD_V=1,INNOV_BAD_P=2,INNOV_BAD_H=4,INNOV_BAD_R=8,
    INNOV_BAD_S=16,INNOV_NONPOSITIVE_S=32,INNOV_BAD_U=64,INNOV_NO_MEMORY=128 };
typedef struct {
    double v,obs_var,pred_var,Sii,sqrt_Sii,u;
    int u_valid,invalid_reason,inactive_state_support;
} ppp_innovation_value_t;
/* Read-only. H is nx*nv, column-major (column i = textbook row h_i).
 * P is nx*nx, R/S are nv*nv, column-major. Optional full_S is independently
 * owned output, never aliased with any input. S=H_rtklib' P H_rtklib+R.
 * Returns 0 when outputs are formed (invalid rows explicitly flagged), -1
 * for bad dimensions/pointers. No active-state compression is applied. */
int ppp_innovation_diag(const double *x,const double *P,const double *v,
    const double *H,const double *R,int nx,int nv,
    ppp_innovation_value_t *out,double *full_S);

/* CSV observer is optional and process-local: one active PC run per writer.
 * Failure to open/write diagnostics never changes estimator return/status. */
int ppp_innovation_log_open(const char *path);
int ppp_innovation_log_close(void);
int ppp_innovation_log_enabled(void);
void ppp_innovation_log_epoch(gtime_t time);
void ppp_innovation_note_spp(int success);
int ppp_innovation_spp_current(void);
void ppp_innovation_core_begin(int nx,const obsd_t *obs,int n,int nf,const rtk_t *rtk);
void ppp_innovation_note_current_init(int state,int sat,int slot,int ambiguity);
void ppp_innovation_note_current_adjustment(int state);
void ppp_innovation_core_events(const obsd_t *obs,int n,const rtk_t *rtk,
    const unsigned char *observed_slip);
void ppp_innovation_meta_provenance(ppp_innovation_meta_t *meta,
    const double *H,int nx,int trial);
void ppp_innovation_log_trial(int trial,int baseline,int corrected,
    const ppp_innovation_meta_t *meta,const ppp_innovation_value_t *value,
    int count,const unsigned char *final_mexc,int mexc_count,
    int epoch_failure,int solution_status,int posterior_committed);
const char *ppp_innovation_class_name(ppp_measurement_class_t c);
#ifdef __cplusplus
}
#endif
#endif
