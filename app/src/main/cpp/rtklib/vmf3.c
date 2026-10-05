/*------------------------------------------------------------------------------
* vmf3.c : VMF3 5x5 degree grid reader for PPP a-priori troposphere modelling
*
* The downloaded files are expected in one directory using the TU Wien names:
* VMF3_yyyyMMdd.H00, VMF3_yyyyMMdd.H06, VMF3_yyyyMMdd.H12, VMF3_yyyyMMdd.H18
* An optional orography_ell_5x5 file in the same directory enables the official
* pressure/exponential height correction for ZHD/ZWD.
*-----------------------------------------------------------------------------*/
#include "rtklib.h"

#define VMF3_NLAT 36
#define VMF3_NLON 72
#define VMF3_NPTS (VMF3_NLAT*VMF3_NLON)
#define VMF3_NVAL 4
#define VMF3_CACHE 4

typedef struct {
    char name[64];
    double *v;                 /* ah, aw, zhd, zwd; south-to-north order */
    unsigned long used;
    int valid;
} vmf3_grid_t;

static char vmf3_dir[1024]="";
static vmf3_grid_t vmf3_cache[VMF3_CACHE];
static double *orog=NULL;      /* ellipsoidal grid heights, south-to-north */
static int orog_loaded=0;
static unsigned long use_count=0;

/* clear cached grids --------------------------------------------------------*/
static void clear_cache(void)
{
    int i;
    for (i=0;i<VMF3_CACHE;i++) {
        free(vmf3_cache[i].v);
        memset(vmf3_cache+i,0,sizeof(vmf3_cache[i]));
    }
    free(orog); orog=NULL;
    orog_loaded=0;
    use_count=0;
}
/* set VMF3 product directory ------------------------------------------------*/
extern void vmf3_set_dir(const char *dir)
{
    char normalized[sizeof(vmf3_dir)];
    size_t n;

    if (!dir) dir="";
    snprintf(normalized,sizeof(normalized),"%s",dir);
    n=strlen(normalized);
    while (n>0&&(normalized[n-1]=='/'||normalized[n-1]=='\\')) {
        normalized[--n]='\0';
    }
    if (strcmp(normalized,vmf3_dir)) {
        clear_cache();
        snprintf(vmf3_dir,sizeof(vmf3_dir),"%s",normalized);
    }
}
/* load grid-point ellipsoidal heights --------------------------------------*/
static void load_orography(void)
{
    FILE *fp;
    char path[1200],line[256];
    double h;
    int i,n=0,row_north,row_south,col;

    if (orog_loaded||!*vmf3_dir) return;
    orog_loaded=1;
    snprintf(path,sizeof(path),"%s/orography_ell_5x5",vmf3_dir);
    if (!(fp=fopen(path,"r"))) {
        trace(2,"vmf3: no orography_ell_5x5 (%s), height correction disabled\n",path);
        return;
    }
    if (!(orog=(double *)calloc(VMF3_NPTS,sizeof(double)))) {
        fclose(fp);
        return;
    }
    while (n<VMF3_NPTS&&fgets(line,sizeof(line),fp)) {
        if (sscanf(line,"%lf",&h)!=1) continue;
        row_north=n/VMF3_NLON;
        row_south=VMF3_NLAT-1-row_north;
        col=n%VMF3_NLON;
        orog[(row_south*VMF3_NLON+col)]=h;
        n++;
    }
    fclose(fp);
    if (n!=VMF3_NPTS) {
        trace(2,"vmf3: invalid orography file (%d/%d points)\n",n,VMF3_NPTS);
        free(orog); orog=NULL;
    }
}
/* form a TU Wien VMF3 filename ---------------------------------------------*/
static void grid_name(gtime_t utc,char *name,size_t n)
{
    double ep[6];
    time2epoch(utc,ep);
    snprintf(name,n,"VMF3_%04.0f%02.0f%02.0f.H%02.0f",ep[0],ep[1],ep[2],ep[3]);
}
/* read one VMF3 text grid ---------------------------------------------------*/
static int read_grid(vmf3_grid_t *grid,const char *name)
{
    FILE *fp;
    char path[1200],line[256];
    double lat,lon,ah,aw,zhd,zwd;
    int row,col,n=0;

    snprintf(path,sizeof(path),"%s/%s",vmf3_dir,name);
    if (!(fp=fopen(path,"r"))) return 0;
    if (!(grid->v=(double *)calloc(VMF3_NPTS*VMF3_NVAL,sizeof(double)))) {
        fclose(fp);
        return 0;
    }
    while (fgets(line,sizeof(line),fp)) {
        if (line[0]=='!'||line[0]=='%') continue;
        if (sscanf(line,"%lf%lf%lf%lf%lf%lf",&lat,&lon,&ah,&aw,&zhd,&zwd)!=6) continue;
        row=(int)floor((lat+87.5)/5.0+0.5);
        col=(int)floor((lon-2.5)/5.0+0.5);
        if (row<0||row>=VMF3_NLAT||col<0||col>=VMF3_NLON) continue;
        grid->v[(row*VMF3_NLON+col)*VMF3_NVAL  ]=ah;
        grid->v[(row*VMF3_NLON+col)*VMF3_NVAL+1]=aw;
        grid->v[(row*VMF3_NLON+col)*VMF3_NVAL+2]=zhd;
        grid->v[(row*VMF3_NLON+col)*VMF3_NVAL+3]=zwd;
        n++;
    }
    fclose(fp);
    if (n!=VMF3_NPTS) {
        trace(2,"vmf3: invalid grid %s (%d/%d points)\n",path,n,VMF3_NPTS);
        free(grid->v); grid->v=NULL;
        return 0;
    }
    snprintf(grid->name,sizeof(grid->name),"%s",name);
    grid->valid=1;
    return 1;
}
/* get a cached grid ---------------------------------------------------------*/
static vmf3_grid_t *get_grid(gtime_t utc)
{
    char name[64];
    vmf3_grid_t *slot=NULL;
    int i;

    if (!*vmf3_dir) return NULL;
    grid_name(utc,name,sizeof(name));
    for (i=0;i<VMF3_CACHE;i++) {
        if (vmf3_cache[i].valid&&!strcmp(vmf3_cache[i].name,name)) {
            vmf3_cache[i].used=++use_count;
            return vmf3_cache+i;
        }
        if (!slot||!vmf3_cache[i].valid||vmf3_cache[i].used<slot->used) slot=vmf3_cache+i;
    }
    if (slot->valid) {
        free(slot->v);
        memset(slot,0,sizeof(*slot));
    }
    if (!read_grid(slot,name)) {
        trace(3,"vmf3: grid not available: %s/%s\n",vmf3_dir,name);
        return NULL;
    }
    slot->used=++use_count;
    load_orography();
    return slot;
}
/* bilinear spatial interpolation -------------------------------------------*/
static void bilinear(const vmf3_grid_t *grid,const double pos[],double out[5])
{
    double lat=pos[0]*R2D,lon=pos[1]*R2D,fy,fx,wy,wx;
    int r0,r1,c0,c1,k,idx[4];

    while (lon<0.0) lon+=360.0;
    while (lon>=360.0) lon-=360.0;
    fy=(lat+87.5)/5.0;
    if (fy<0.0) fy=0.0;
    if (fy>VMF3_NLAT-1) fy=VMF3_NLAT-1;
    r0=(int)floor(fy); r1=r0<VMF3_NLAT-1?r0+1:r0; wy=fy-r0;

    fx=(lon-2.5)/5.0;
    while (fx<0.0) fx+=VMF3_NLON;
    while (fx>=VMF3_NLON) fx-=VMF3_NLON;
    c0=(int)floor(fx); c1=(c0+1)%VMF3_NLON; wx=fx-c0;
    idx[0]=r0*VMF3_NLON+c0; idx[1]=r0*VMF3_NLON+c1;
    idx[2]=r1*VMF3_NLON+c0; idx[3]=r1*VMF3_NLON+c1;

    for (k=0;k<VMF3_NVAL;k++) {
        double south=grid->v[idx[0]*VMF3_NVAL+k]*(1.0-wx)+grid->v[idx[1]*VMF3_NVAL+k]*wx;
        double north=grid->v[idx[2]*VMF3_NVAL+k]*(1.0-wx)+grid->v[idx[3]*VMF3_NVAL+k]*wx;
        out[k]=south*(1.0-wy)+north*wy;
    }
    if (orog) {
        double south=orog[idx[0]]*(1.0-wx)+orog[idx[1]]*wx;
        double north=orog[idx[2]]*(1.0-wx)+orog[idx[3]]*wx;
        out[4]=south*(1.0-wy)+north*wy;
    }
    else out[4]=pos[2];
}
/* continued-fraction mapping function --------------------------------------*/
static double vmf_map(double el,double a,double b,double c)
{
    double sinel=sin(el);
    return (1.0+a/(1.0+b/(1.0+c)))/(sinel+a/(sinel+b/(sinel+c)));
}
/* interpolate VMF3 grids and form PPP troposphere parameters ---------------*/
extern int vmf3_trop(gtime_t time,const double pos[],const double azel[],
                     double *mfh,double *mfw,double *zhd,double *zwd)
{
    double ep[6],sod,alpha,p0[5],p1[5],p[5],den,pres,htcorr;
    gtime_t utc,day,t0,t1;
    vmf3_grid_t *g0,*g1;
    int i;

    if (!*vmf3_dir||azel[1]<=0.0) return 0;
    utc=gpst2utc(time);
    time2epoch(utc,ep);
    sod=ep[3]*3600.0+ep[4]*60.0+ep[5];
    day=epoch2time((double[]){ep[0],ep[1],ep[2],0,0,0});
    t0=timeadd(day,floor(sod/21600.0)*21600.0);
    t1=timeadd(t0,21600.0);
    alpha=timediff(utc,t0)/21600.0;

    if (!(g0=get_grid(t0))) return 0;
    bilinear(g0,pos,p0);
    if ((g1=get_grid(t1))) {
        bilinear(g1,pos,p1);
        for (i=0;i<5;i++) p[i]=p0[i]+(p1[i]-p0[i])*alpha;
    }
    else {
        /* H18 can be used alone if the next-day H00 forecast was not fetched. */
        memcpy(p,p0,sizeof(p));
        trace(2,"vmf3: next grid missing, using nearest VMF3 epoch only\n");
    }

    /* Kouba (2008) height correction, using the supplied grid orography. */
    den=1.0-0.00266*cos(2.0*pos[0])-0.00000028*p[4];
    pres=p[2]/0.0022768*den;
    pres*=pow(1.0-0.0000226*(pos[2]-p[4]),5.225);
    *zhd=0.0022768*pres/(1.0-0.00266*cos(2.0*pos[0])-0.00000028*pos[2]);
    *zwd=p[3]*exp(-(pos[2]-p[4])/2000.0);

    /* VMF3 provides a_h/a_w. The standard NMF b/c terms keep this compact
       implementation compatible with RTKLIB while preserving VMF3's dynamic a. */
    *mfh=vmf_map(azel[1],p[0],0.0029,0.0630);
    *mfw=vmf_map(azel[1],p[1],0.00146,0.04391);
    htcorr=(1.0/sin(azel[1])-vmf_map(azel[1],2.53E-5,5.49E-3,1.14E-3))*pos[2]/1000.0;
    *mfh+=htcorr;
    return 1;
}
