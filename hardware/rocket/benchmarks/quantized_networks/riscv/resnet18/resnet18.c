#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include <limits.h>
#include "parameters.h"

typedef struct {int c,h,w;float *v;} Tensor;
static const char *directory;
static size_t elems(Tensor a){return (size_t)a.c*a.h*a.w;}
static void die(const char *msg){fprintf(stderr,"ERROR %s\n",msg);exit(2);}
static void path(char *dst,size_t n,const char *file){
  if(snprintf(dst,n,"%s/%s",directory,file)>=(int)n) die("path too long");
}
static void read_exact(const char *file,void *data,size_t bytes){
  char p[4096];path(p,sizeof(p),file);FILE *f=fopen(p,"rb");
  if(!f){perror(p);exit(2);}
  if(fread(data,1,bytes,f)!=bytes||fgetc(f)!=EOF) die("invalid tensor byte length");
  fclose(f);
}
static void* allocate(size_t bytes){
  void *v=malloc(bytes?bytes:1);if(!v) die("malloc failed");return v;
}
static Tensor create(int c,int h,int w){
  if(c<=0||h<=0||w<=0) die("invalid shape");
  Tensor t={c,h,w,allocate((size_t)c*h*w*sizeof(float))};return t;
}
static void dispose(Tensor *t){free(t->v);t->v=NULL;}
static const QDesc *qd(const char *name){
  for(size_t i=0;i<sizeof(QL)/sizeof(QL[0]);i++)
    if(strcmp(QL[i].name,name)==0) return &QL[i];
  fprintf(stderr,"missing Q layer %s\n",name);exit(2);
}
static const BNDesc *bd(const char *name){
  for(size_t i=0;i<sizeof(BL)/sizeof(BL[0]);i++)
    if(strcmp(BL[i].name,name)==0) return &BL[i];
  fprintf(stderr,"missing BN layer %s\n",name);exit(2);
}
static void checkpoint(const char *name,Tensor a){
  char fname[128];size_t p=0;const char *s=name;
  strcpy(fname,"ref_");p=4;
  while(*s){if(p+6>=sizeof(fname)) die("long layer name");fname[p++]=*s=='.'?'_':*s;s++;}
  strcpy(fname+p,".bin");
  size_t n=elems(a);float *ref=allocate(n*sizeof(float));read_exact(fname,ref,n*sizeof(float));
  float maximum=0.0f;size_t bad=0;size_t first=0;
  for(size_t i=0;i<n;i++){
    float err=fabsf(a.v[i]-ref[i]);if(err>maximum) maximum=err;
    float tolerance=5e-4f+2e-3f*fabsf(ref[i]);
    if(!isfinite(a.v[i])||!isfinite(ref[i])||err>tolerance){if(bad==0)first=i;bad++;}
  }
  printf("CHECK %-24s elems=%zu maxerr=%.7g mismatches=%zu\n",name,n,maximum,bad);
  if(bad){fprintf(stderr,"FAILED %s first index=%zu got=%.8g expected=%.8g\n",name,first,a.v[first],ref[first]);exit(1);}
  free(ref);
}
static Tensor quantized(const char *name,Tensor input){
  const QDesc *q=qd(name);
  if(q->ci!=input.c||q->groups!=1)die("invalid Q input shape");
  int oh=(input.h+2*q->ph-q->dh*(q->kh-1)-1)/q->sh+1;
  int ow=(input.w+2*q->pw-q->dw*(q->kw-1)-1)/q->sw+1;
  if(q->linear&&(input.h!=1||input.w!=1))die("linear shape");
  Tensor out=create(q->co,oh,ow);
  size_t wn=(size_t)q->co*q->ci*q->kh*q->kw;
  int8_t *weights=allocate(wn);float *scale=allocate((size_t)q->co*4);
  float *bias=allocate((size_t)q->co*4);
  char filename[80];
  snprintf(filename,sizeof(filename),"q%02d_w.bin",q->id);read_exact(filename,weights,wn);
  snprintf(filename,sizeof(filename),"q%02d_scale.bin",q->id);read_exact(filename,scale,(size_t)q->co*4);
  snprintf(filename,sizeof(filename),"q%02d_bias.bin",q->id);read_exact(filename,bias,(size_t)q->co*4);
  size_t inlen=elems(input);int16_t *xq=allocate(inlen*sizeof(int16_t));
  int qmax=(1<<q->bits)-1;
  for(size_t i=0;i<inlen;i++){
    float z=nearbyintf(input.v[i]/q->scale+q->zp);
    if(z<0) z=0;
    if(z>qmax) z=qmax;
    xq[i]=(int16_t)((int)z-(int)q->zp);
  }
  for(int oc=0;oc<q->co;oc++){
    float combined=scale[oc]*q->scale;
    for(int oy=0;oy<oh;oy++) for(int ox=0;ox<ow;ox++){
      int32_t acc=0;
      for(int ic=0;ic<q->ci;ic++){
        for(int ky=0;ky<q->kh;ky++){
          int iy=oy*q->sh-q->ph+ky*q->dh;
          if(iy<0||iy>=input.h)continue;
          for(int kx=0;kx<q->kw;kx++){
            int ix=ox*q->sw-q->pw+kx*q->dw;
            if(ix<0||ix>=input.w)continue;
            size_t xi=((size_t)ic*input.h+iy)*input.w+ix;
            size_t wi=(((size_t)oc*q->ci+ic)*q->kh+ky)*q->kw+kx;
            acc+=(int32_t)xq[xi]*(int32_t)weights[wi];
          }
        }
      }
      out.v[((size_t)oc*oh+oy)*ow+ox]=(float)acc*combined+bias[oc];
    }
  }
  free(weights);free(scale);free(bias);free(xq);
  checkpoint(name,out);return out;
}
static Tensor bn(const char *name,Tensor input){
  const BNDesc *b=bd(name);if(b->channels!=input.c)die("BN channels");
  float *g=allocate((size_t)b->channels*4),*bias=allocate((size_t)b->channels*4);
  float *mean=allocate((size_t)b->channels*4),*var=allocate((size_t)b->channels*4);
  char f[80];
  snprintf(f,sizeof(f),"bn%02d_weight.bin",b->id);read_exact(f,g,(size_t)b->channels*4);
  snprintf(f,sizeof(f),"bn%02d_bias.bin",b->id);read_exact(f,bias,(size_t)b->channels*4);
  snprintf(f,sizeof(f),"bn%02d_running_mean.bin",b->id);read_exact(f,mean,(size_t)b->channels*4);
  snprintf(f,sizeof(f),"bn%02d_running_var.bin",b->id);read_exact(f,var,(size_t)b->channels*4);
  Tensor output=create(input.c,input.h,input.w);
  size_t spatial=(size_t)input.h*input.w;
  for(int c=0;c<input.c;c++){
    float mult=g[c]/sqrtf(var[c]+b->eps);
    for(size_t i=0;i<spatial;i++){
      size_t j=(size_t)c*spatial+i;
      output.v[j]=(input.v[j]-mean[c])*mult+bias[c];
    }
  }
  free(g);free(bias);free(mean);free(var);
  checkpoint(name,output);return output;
}
static Tensor apply_q(const char *name,Tensor input){Tensor y=quantized(name,input);dispose(&input);return y;}
static Tensor apply_bn(const char *name,Tensor input){Tensor y=bn(name,input);dispose(&input);return y;}
static void relu(Tensor *t){for(size_t i=0;i<elems(*t);i++)if(t->v[i]<0)t->v[i]=0;}
static Tensor block(int stage,int n,Tensor input){
  char name[100];snprintf(name,sizeof(name),"layer%d.%d",stage,n);
  char leaf[128];
  snprintf(leaf,sizeof(leaf),"%s.conv1",name);Tensor main=quantized(leaf,input);
  snprintf(leaf,sizeof(leaf),"%s.bn1",name);main=apply_bn(leaf,main);relu(&main);
  snprintf(leaf,sizeof(leaf),"%s.conv2",name);main=apply_q(leaf,main);
  snprintf(leaf,sizeof(leaf),"%s.bn2",name);main=apply_bn(leaf,main);
  Tensor skip;
  if(stage>1&&n==0){
    snprintf(leaf,sizeof(leaf),"%s.downsample.0",name);skip=quantized(leaf,input);
    snprintf(leaf,sizeof(leaf),"%s.downsample.1",name);skip=apply_bn(leaf,skip);
  } else {skip=input;}
  if(elems(skip)!=elems(main))die("residual shape mismatch");
  for(size_t i=0;i<elems(main);i++){
    float v=main.v[i]+skip.v[i];main.v[i]=v>0?v:0;
  }
  checkpoint(name,main);
  if(skip.v!=input.v)dispose(&skip);
  dispose(&input);
  return main;
}
static Tensor avgpool(Tensor input){
  Tensor y=create(input.c,1,1);int size=input.h*input.w;
  for(int c=0;c<input.c;c++){
    float acc=0;for(int i=0;i<size;i++)acc+=input.v[(size_t)c*size+i];
    y.v[c]=acc/(float)size;
  }
  checkpoint("avgpool",y);dispose(&input);return y;
}
int main(int argc,char **argv){
  if(argc!=2)die("usage: resnet18 executable DATA_DIR");
  directory=argv[1];
  Tensor x=create(3,32,32);read_exact("input.bin",x.v,elems(x)*sizeof(float));
  x=apply_q("conv1",x);x=apply_bn("bn1",x);relu(&x);
  for(int layer=1;layer<=4;layer++)for(int b=0;b<2;b++)x=block(layer,b,x);
  x=avgpool(x);x=apply_q("fc",x);
  if(x.c!=43||x.h!=1||x.w!=1)die("unexpected final shape");
  int pred=0;for(int i=1;i<43;i++)if(x.v[i]>x.v[pred])pred=i;
  printf("FULL_RESNET18_RISCV_RESULT class=%d top_logit=%.8g\n",pred,x.v[pred]);
  dispose(&x);
  if(pred!=25){fprintf(stderr,"WRONG PREDICTION expected=25 got=%d\n",pred);return 1;}
  puts("PASS: COMPLETE ResNet18 inference, all 50 module checks and class match");
  return 0;
}
