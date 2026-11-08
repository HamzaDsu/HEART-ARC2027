#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include "parameters.h"
typedef struct {int c,h,w;float *v;} Tensor;
static const char *directory;
static void die(const char *msg){fprintf(stderr,"ERROR: %s\n",msg);exit(2);}
static size_t elems(Tensor a){return (size_t)a.c*a.h*a.w;}
static void read_exact(const char *file,void *ptr,size_t n){
  char path[4096];
  if(snprintf(path,sizeof(path),"%s/%s",directory,file)>=(int)sizeof(path))die("path too long");
  FILE *f=fopen(path,"rb");if(!f){perror(path);exit(2);}
  if(fread(ptr,1,n,f)!=n||fgetc(f)!=EOF)die("invalid binary size");
  fclose(f);
}
static void *allocate(size_t size){void *p=malloc(size?size:1);if(!p)die("malloc failed");return p;}
static Tensor create(int c,int h,int w){
  if(c<=0||h<=0||w<=0)die("invalid tensor shape");
  Tensor t={c,h,w,allocate((size_t)c*h*w*sizeof(float))};return t;
}
static void dispose(Tensor *t){free(t->v);t->v=NULL;}
static const QDesc *qd(int id){if(id<0||id>=(int)(sizeof(QL)/sizeof(*QL)))die("invalid Q id");return &QL[id];}
static const BNDesc *bd(int id){if(id<0||id>=(int)(sizeof(BL)/sizeof(*BL)))die("invalid BN id");return &BL[id];}
static void checkpoint(int id,Tensor x){
  const Stage *s=&SL[id];char file[40];snprintf(file,sizeof(file),"ref_%03d.bin",id);
  size_t n=elems(x);float *ref=allocate(n*sizeof(float));read_exact(file,ref,n*sizeof(float));
  size_t failures=0,first=0;float maxerr=0;
  for(size_t i=0;i<n;i++){
    float error=fabsf(x.v[i]-ref[i]);if(error>maxerr)maxerr=error;
    float tolerance=5e-4f+2e-3f*fabsf(ref[i]);
    if(!isfinite(x.v[i])||!isfinite(ref[i])||error>tolerance){if(!failures)first=i;failures++;}
  }
  printf("CHECK %-24s elems=%zu maxerr=%.8g mismatches=%zu\n",s->name,n,maxerr,failures);
  if(failures){fprintf(stderr,"FAIL %s first=%zu got=%.9g expected=%.9g\n",s->name,first,x.v[first],ref[first]);free(ref);exit(1);}
  free(ref);
}
static void relu(Tensor *x,int relu6){
  for(size_t i=0;i<elems(*x);i++){
    float v=x->v[i];if(v<0)v=0;if(relu6&&v>6)v=6;x->v[i]=v;
  }
}
static Tensor quantized(int id,Tensor input,int oc,int oh,int ow){
  const QDesc *q=qd(id);
  if(q->ci!=input.c||q->groups!=1)die("invalid quant input shape");
  int calculated_h=(input.h+2*q->ph-q->dh*(q->kh-1)-1)/q->sh+1;
  int calculated_w=(input.w+2*q->pw-q->dw*(q->kw-1)-1)/q->sw+1;
  if(calculated_h!=oh||calculated_w!=ow||q->co!=oc)die("wrong quant output shape");
  if(q->linear&&(input.h!=1||input.w!=1))die("linear expects flattened input");
  Tensor out=create(oc,oh,ow);
  size_t wn=(size_t)q->co*q->ci*q->kh*q->kw;
  int8_t *weights=allocate(wn);float *scales=allocate((size_t)q->co*4),*bias=allocate((size_t)q->co*4);
  char file[80];
  snprintf(file,sizeof(file),"q%02d_w.bin",q->id);read_exact(file,weights,wn);
  snprintf(file,sizeof(file),"q%02d_scale.bin",q->id);read_exact(file,scales,(size_t)q->co*4);
  snprintf(file,sizeof(file),"q%02d_bias.bin",q->id);read_exact(file,bias,(size_t)q->co*4);
  size_t n=elems(input);int16_t *xq=allocate(n*sizeof(int16_t));int qmax=(1<<q->bits)-1;
  for(size_t i=0;i<n;i++){
    float z=nearbyintf(input.v[i]/q->scale+q->zp);
    if(z<0)z=0;
    if(z>qmax)z=(float)qmax;
    xq[i]=(int16_t)((int)z-(int)q->zp);
  }
  for(int c=0;c<oc;c++){
    float combined=scales[c]*q->scale;
    for(int y=0;y<oh;y++)for(int x=0;x<ow;x++){
      int32_t acc=0;
      for(int ic=0;ic<q->ci;ic++)for(int ky=0;ky<q->kh;ky++){
        int iy=y*q->sh-q->ph+ky*q->dh;
        if(iy<0||iy>=input.h)continue;
        for(int kx=0;kx<q->kw;kx++){
          int ix=x*q->sw-q->pw+kx*q->dw;
          if(ix<0||ix>=input.w)continue;
          size_t wi=(((size_t)c*q->ci+ic)*q->kh+ky)*q->kw+kx;
          size_t xi=((size_t)ic*input.h+iy)*input.w+ix;
          acc+=(int32_t)xq[xi]*(int32_t)weights[wi];
        }
      }
      out.v[((size_t)c*oh+y)*ow+x]=(float)acc*combined+bias[c];
    }
  }
  free(weights);free(scales);free(bias);free(xq);return out;
}
static Tensor bn(int id,Tensor input){
  const BNDesc *b=bd(id);if(b->channels!=input.c)die("BN channel mismatch");
  size_t count=(size_t)b->channels;
  float *gamma=allocate(count*4),*bias=allocate(count*4),*mean=allocate(count*4),*var=allocate(count*4);
  char file[80];
  snprintf(file,sizeof(file),"bn%02d_weight.bin",b->id);read_exact(file,gamma,count*4);
  snprintf(file,sizeof(file),"bn%02d_bias.bin",b->id);read_exact(file,bias,count*4);
  snprintf(file,sizeof(file),"bn%02d_running_mean.bin",b->id);read_exact(file,mean,count*4);
  snprintf(file,sizeof(file),"bn%02d_running_var.bin",b->id);read_exact(file,var,count*4);
  Tensor out=create(input.c,input.h,input.w);size_t sp=(size_t)input.h*input.w;
  for(int c=0;c<input.c;c++){
    float mul=gamma[c]/sqrtf(var[c]+b->eps);
    for(size_t i=0;i<sp;i++){
      size_t j=(size_t)c*sp+i;
      out.v[j]=(input.v[j]-mean[c])*mul+bias[c];
    }
  }
  free(gamma);free(bias);free(mean);free(var);return out;
}
static Tensor pooling(const Stage *s,Tensor x,int type){
  Tensor y=create(s->oc,s->oh,s->ow);
  if(s->oc!=x.c)die("pool channel mismatch");
  for(int c=0;c<x.c;c++)for(int oy=0;oy<s->oh;oy++)for(int ox=0;ox<s->ow;ox++){
    float val=(type==5)?-INFINITY:0.0f;int n=0;
    int ystart,yend,xstart,xend;
    if(type==7){
      ystart=(int)floorf((float)(oy*x.h)/(float)s->oh);
      yend=(int)ceilf((float)((oy+1)*x.h)/(float)s->oh);
      xstart=(int)floorf((float)(ox*x.w)/(float)s->ow);
      xend=(int)ceilf((float)((ox+1)*x.w)/(float)s->ow);
    } else {
      ystart=oy*s->sh-s->ph;xstart=ox*s->sw-s->pw;
      yend=ystart+s->kh*s->dh;xend=xstart+s->kw*s->dw;
    }
    for(int iy=ystart;iy<yend;iy+=((type==7)?1:s->dh)){
      for(int ix=xstart;ix<xend;ix+=((type==7)?1:s->dw)){
        if(iy<0||iy>=x.h||ix<0||ix>=x.w)continue;
        float v=x.v[((size_t)c*x.h+iy)*x.w+ix];
        if(type==5){if(v>val)val=v;}else{val+=v;n++;}
      }
    }
    if(type!=5){
      if(type==6 && s->include_pad){
        n=s->kh*s->kw;
      }
      if(!n)die("empty average pool window");
      val/=(float)n;
    }
    y.v[((size_t)c*s->oh+oy)*s->ow+ox]=val;
  }
  return y;
}
int main(int argc,char **argv){
  if(argc!=2)die("usage: vgg11_executable DATA_DIR");
  directory=argv[1];
  if(sizeof(SL)/sizeof(*SL)==0)die("no stages");
  Tensor x=create(SL[0].ic,SL[0].ih,SL[0].iw);
  read_exact("input.bin",x.v,elems(x)*sizeof(float));
  for(size_t i=0;i<sizeof(SL)/sizeof(*SL);i++){
    const Stage *s=&SL[i];
    if(s->implicit)relu(&x,0);
    if(elems(x)!=(size_t)s->ic*s->ih*s->iw)die("graph connection failed");
    x.c=s->ic;x.h=s->ih;x.w=s->iw;
    Tensor y;
    if(s->op==1)y=quantized(s->id,x,s->oc,s->oh,s->ow);
    else if(s->op==2)y=bn(s->id,x);
    else if(s->op==3||s->op==4){relu(&x,s->op==4);y=x;x.v=NULL;}
    else if(s->op==5||s->op==6||s->op==7)y=pooling(s,x,s->op);
    else if(s->op==8){y=x;x.v=NULL;}
    else die("unknown operation type");
    dispose(&x);
    if(elems(y)!=(size_t)s->oc*s->oh*s->ow)die("module output shape mismatch");
    y.c=s->oc;y.h=s->oh;y.w=s->ow;
    checkpoint((int)i,y);
    x=y;
  }
  if(elems(x)!=10)die("wrong classifier size");
  int pred=0;for(int i=1;i<10;i++)if(x.v[i]>x.v[pred])pred=i;
  printf("FULL_VGG11_RISCV_RESULT class=%d top_logit=%.9g\n",pred,x.v[pred]);
  dispose(&x);
  puts("PASS: COMPLETE VGG11 inference and all module checks");
  return 0;
}
