#!/usr/bin/env python3
"""Convert an existing Mahdi VGG11 MQF CPU trace/export to connected native/RISC-V C inference.

Run from health-slm-riscv-hardware repository. Does not use traced intermediate inputs
as C inputs: C receives only the initial input tensor, quantization weights, and BN
parameters. Intermediate reference tensors are used *only* for verification.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path
import hashlib
import json
import math
import shutil
import subprocess
import sys
import numpy as np

repo = Path.cwd()
assert (repo / 'benchmarks/quantized_networks/riscv/vgg11/capture_vgg11.py').is_file(), 'Run from research repo root; VGG capture script missing'
external = Path.home() / 'health-slm-model-validation'
source = external / 'vgg11-full-reference-export-20261029'
out = external / 'vgg11-full-c-riscv-20261029'
sources = repo / 'benchmarks/quantized_networks/riscv/vgg11'
evidence = repo / 'results/model_execution/vgg11_full_c_riscv_qemu_20261029'
assert (source / 'manifest.json').is_file(), 'Run capture_vgg11.py first'
assert not out.exists(), f'Output directory already exists, refusing overwrite: {out}'
assert not (sources / 'vgg11.c').exists(), 'Existing generated C file, refusing overwrite'
assert not (sources / 'parameters.h').exists(), 'Existing generated header, refusing overwrite'
assert not evidence.exists(), 'Existing evidence directory, refusing overwrite'
manifest = json.loads((source / 'manifest.json').read_text())
assert manifest['model'] == 'Mahdi VGG11 CIFAR10'
assert int(manifest['quantized_layers']) == 11
assert int(manifest['module_calls']) == len(manifest['trace'])
assert manifest['logits']['shape'] == [1, 10]
for tool in ('gcc','riscv64-linux-gnu-gcc','qemu-riscv64'):
    assert shutil.which(tool), f'Missing tool: {tool}'

mods = {m['name']: m for m in manifest['modules']}
trace = manifest['trace']
assert len(mods) == len(manifest['modules']), 'Duplicate module names'
assert len(trace) >= 11


def checksum(path: Path) -> str:
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024), b''):
            h.update(chunk)
    return h.hexdigest()


def tensor(info: dict, binary: bool) -> np.ndarray:
    path=source/info['file']
    assert path.is_file(),f'Missing tensor: {path}'
    assert checksum(path)==info['sha256'],f'Hash mismatch: {path}'
    dtype=np.dtype(info['dtype'])
    assert dtype==np.dtype('<f4'),f'Expected float32; got {dtype}: {path}'
    if binary:
        arr=np.fromfile(path,dtype=dtype).reshape(info['shape'])
    else:
        arr=np.load(path,allow_pickle=False)
    assert list(arr.shape)==info['shape']
    assert arr.dtype==np.float32
    assert np.all(np.isfinite(arr)),f'Non-finite values: {path}'
    return arr


def attr2(attrs:dict,key:str,default:int) -> tuple[int,int]:
    x=attrs.get(key,default)
    if isinstance(x,int):return x,x
    assert len(x)==2 and all(isinstance(v,int) for v in x),(key,x)
    return int(x[0]),int(x[1])


def shape3(shape:list) -> tuple[int,int,int]:
    assert len(shape) in (2,4) and shape[0]==1, f'Unsupported model input/output shape: {shape}'
    return (int(shape[1]),1,1) if len(shape)==2 else tuple(map(int,shape[1:]))


def ffloat(x) -> str:
    v=float(x)
    assert math.isfinite(v)
    return f'{v:.9e}f'

# Construct connected graph from actual hook execution order. Additional operations
# not exposed by hooks may be admitted only if they are provably identity/reshape/ReLU.
allowed={'IntegerConv2d','IntegerLinear','BatchNorm2d','BatchNorm1d',
         'ReLU','ReLU6','MaxPool2d','AvgPool2d','AdaptiveAvgPool2d',
         'Dropout','Dropout2d','Identity','Flatten'}
unsupported=sorted(set(e['type'] for e in trace)-allowed)
assert not unsupported, f'Unsupported VGG operators: {unsupported}'

qnames=[m['name'] for m in manifest['modules'] if m['type'] in ('IntegerConv2d','IntegerLinear')]
bnames=[m['name'] for m in manifest['modules'] if m['type'] in ('BatchNorm2d','BatchNorm1d')]
assert len(qnames)==11
qindex={n:i for i,n in enumerate(qnames)}
bindex={n:i for i,n in enumerate(bnames)}

weights={}
qrecords=[]
brecords=[]
for i,name in enumerate(qnames):
    mod=mods[name]
    w=tensor(mod['tensors']['weight_float'],True)
    sc=tensor(mod['tensors']['scale_w'],True)
    act_scale=float(tensor(mod['tensors']['act_scale'],True).item())
    zp=float(tensor(mod['tensors']['act_zero_point'],True).item())
    bits=mod['attributes']['weight_bits'];ba=int(mod['attributes']['act_bit_width'])
    assert mod['attributes']['calibrated'] is True, f'Layer not calibrated: {name}'
    assert all(2<=int(b)<=8 for b in bits), (name,set(bits))
    assert 1<=ba<=8 and act_scale>0 and math.isfinite(zp)
    assert len(bits)==w.shape[0] and sc.shape==(w.shape[0],)
    q=np.empty(w.shape,dtype=np.int8)
    for ch,bw in enumerate(bits):
        qmax=(1<<(int(bw)-1))-1
        qmin=-(1<<(int(bw)-1))
        calcscale=max(float(np.max(np.abs(w[ch]))/np.float32(qmax)),1e-8)
        assert np.isclose(calcscale,sc[ch],rtol=1e-5,atol=1e-8),(name,ch,calcscale,float(sc[ch]))
        q[ch]=np.clip(np.rint(w[ch]/sc[ch]),qmin,qmax).astype(np.int8)
    bias=tensor(mod['tensors']['bias_float'],True) if 'bias_float' in mod['tensors'] else np.zeros(w.shape[0],dtype=np.float32)
    assert bias.shape==(w.shape[0],)
    weights[f'q{i:02}_w.bin']=q
    weights[f'q{i:02}_scale.bin']=sc.astype('<f4')
    weights[f'q{i:02}_bias.bin']=bias.astype('<f4')
    a=mod['attributes']
    if mod['type']=='IntegerConv2d':
        assert w.ndim==4,(name,w.shape)
        co,ci,kh,kw=map(int,w.shape)
        sh,sw=attr2(a,'stride',1)
        ph,pw=attr2(a,'padding',0)
        dh,dw=attr2(a,'dilation',1)
        groups=int(a.get('groups',1))
        assert groups==1,(name,groups)
        vals=[co,ci,kh,kw,sh,sw,ph,pw,dh,dw,groups,ba,0]
    else:
        assert w.ndim==2,(name,w.shape)
        co,ci=map(int,w.shape)
        vals=[co,ci,1,1,1,1,0,0,1,1,1,ba,1]
    qrecords.append(f'  {{"{name}",{i},'+','.join(map(str,vals))+f',{ffloat(act_scale)},{ffloat(zp)}}},')

for i,name in enumerate(bnames):
    mod=mods[name]
    g=tensor(mod['tensors']['weight'],True)
    count=int(g.size)
    assert g.shape==(count,)
    for key in ('weight','bias','running_mean','running_var'):
        a=tensor(mod['tensors'][key],True)
        assert a.shape==(count,)
        weights[f'bn{i:02}_{key}.bin']=a.astype('<f4')
    eps=float(mod['attributes'].get('eps',1e-5))
    brecords.append(f'  {{"{name}",{i},{count},{ffloat(eps)}}},')

stage_records=[]
prev=tensor(manifest['input'],False)
reference={}
implicit_counts=Counter()
for j,e in enumerate(trace):
    idx=int(e['index']);name=e['name'];kind=e['type']
    assert idx==j,f'Non-contiguous trace: {idx} vs {j}'
    assert name in mods,f'Module not exported: {name}'
    x=tensor(e['input'],False)
    y=tensor(e['output'],False)
    assert x.size==prev.size,f'Unexplained tensor-size change before {name}'
    if np.array_equal(prev.reshape(-1),x.reshape(-1)):
        implicit=0
    elif np.allclose(np.maximum(prev,0).reshape(-1),x.reshape(-1),rtol=1e-6,atol=1e-7):
        implicit=1
        implicit_counts['functional_relu']+=1
    else:
        diff=float(np.max(np.abs(prev.reshape(-1)-x.reshape(-1))))
        raise AssertionError(f'Unexplained transform before {name}: max difference {diff}; cannot claim connected inference')
    ic,ih,iw=shape3(e['input']['shape'])
    oc,oh,ow=shape3(e['output']['shape'])
    assert ic*ih*iw==x.size and oc*oh*ow==y.size
    a=mods[name]['attributes']
    params=[0]*10
    if kind in ('IntegerConv2d','IntegerLinear'):
        op=1;layer_id=qindex[name]
    elif kind in ('BatchNorm2d','BatchNorm1d'):
        op=2;layer_id=bindex[name]
    elif kind=='ReLU':op=3;layer_id=0
    elif kind=='ReLU6':op=4;layer_id=0
    elif kind=='MaxPool2d':
        op=5;layer_id=0
        kh,kw=attr2(a,'kernel_size',2)
        sh,sw=attr2(a,'stride',kh) if a.get('stride') is not None else (kh,kw)
        ph,pw=attr2(a,'padding',0)
        dh,dw=attr2(a,'dilation',1)
        params=[kh,kw,sh,sw,ph,pw,dh,dw,int(bool(a.get('ceil_mode',False))),0]
    elif kind=='AvgPool2d':
        op=6;layer_id=0
        kh,kw=attr2(a,'kernel_size',2)
        sh,sw=attr2(a,'stride',kh) if a.get('stride') is not None else (kh,kw)
        ph,pw=attr2(a,'padding',0)
        params=[kh,kw,sh,sw,ph,pw,1,1,int(bool(a.get('ceil_mode',False))),int(bool(a.get('count_include_pad',True)))]
    elif kind=='AdaptiveAvgPool2d':op=7;layer_id=0
    elif kind in ('Dropout','Dropout2d','Identity','Flatten'):op=8;layer_id=0
    else:raise AssertionError(kind)
    reference[f'ref_{idx:03}.bin']=y.astype('<f4')
    vals=[op,layer_id,implicit,ic,ih,iw,oc,oh,ow,*params]
    stage_records.append('  {"'+name+'",'+','.join(map(str,vals))+'},')
    print(f'GRAPH {j:02d} {name:<23} {kind:<19} {str(tuple(x.shape)):<21} -> {tuple(y.shape)}' + ('  [inferred functional ReLU]' if implicit else ''))
    prev=y

start=tensor(manifest['input'],False)
final=tensor(manifest['logits'],False)
assert np.allclose(prev,final,rtol=1e-6,atol=1e-6),'Final leaf output differs from full logits (unsupported final op)'
assert int(np.argmax(final))==int(manifest['predicted_class'])
weights['input.bin']=start.astype('<f4')
weights.update(reference)

# Everything validated; only now create artifacts.
out.mkdir(parents=True)
for name,arr in weights.items():
    np.ascontiguousarray(arr).tofile(out/name)

header='''// Auto-generated from the Mahdi VGG11 CPU reference; do not edit manually.
typedef struct {const char *name;int id,co,ci,kh,kw,sh,sw,ph,pw,dh,dw,groups,bits,linear;float scale,zp;} QDesc;
typedef struct {const char *name;int id,channels;float eps;} BNDesc;
typedef struct {const char *name;int op,id,implicit,ic,ih,iw,oc,oh,ow;int kh,kw,sh,sw,ph,pw,dh,dw,ceil_mode,include_pad;} Stage;
static const QDesc QL[]={
'''+ '\n'.join(qrecords) + '\n};\nstatic const BNDesc BL[]={\n' + '\n'.join(brecords) + '\n};\nstatic const Stage SL[]={\n' + '\n'.join(stage_records) + '\n};\n'

# The C source deliberately does not read trace input tensors; only 'input.bin'
# and original exported weights, BN stats and captured *output* references.
csource=r'''#include <stdio.h>
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
'''

(sources / 'parameters.h').write_text(header)
(sources / 'vgg11.c').write_text(csource)
(out / 'export_info.json').write_text(json.dumps({
    'model':'Mahdi VGG11 CIFAR10',
    'original_manifest_sha256':checksum(source/'manifest.json'),
    'reference_class':int(manifest['predicted_class']),
    'reference_label':int(manifest['reference_label']),
    'label_available':int(manifest['reference_label'])>=0,
    'quantized_layers':qnames,'batchnorm_layers':bnames,
    'module_checks':len(trace),'implicit_operations':dict(implicit_counts),
    'tensor_binary_files':len(weights),
    'whole_rocket_rtl':False,'physical_fpga':False
},indent=2)+'\n')


def run(args:list[str], log:str) -> str:
    print('$',' '.join(map(str,args)),flush=True)
    p=subprocess.run([str(a) for a in args],cwd=sources,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    (out/log).write_text(p.stdout)
    lines=p.stdout.splitlines()
    for line in lines[-16:]:print(line,flush=True)
    if p.returncode:raise SystemExit(f'FAILED {args[0]} exit={p.returncode}; read {out/log}')
    return p.stdout

print(f'Prepared {len(trace)} connected VGG11 operators, {len(qnames)} quantized layers, {len(bnames)} BN layers',flush=True)
run(['gcc','-O2','-std=c11','-Wall','-Wextra','-ffp-contract=off','vgg11.c','-lm','-o',str(out/'vgg11_native')],'compile_native.txt')
run(['riscv64-linux-gnu-gcc','-O2','-std=c11','-Wall','-Wextra','-ffp-contract=off','-march=rv64gc','-mabi=lp64d','-static','vgg11.c','-lm','-o',str(out/'vgg11_riscv')],'compile_riscv.txt')
logs=[]
for platform,args in (
    ('native',[out/'vgg11_native',out]),
    ('qemu',['qemu-riscv64',out/'vgg11_riscv',out]),
):
    stdout=run(args,platform+'.txt')
    checks=[line for line in stdout.splitlines() if line.startswith('CHECK ')]
    assert len(checks)==len(trace),f'Expected {len(trace)} checks, found {len(checks)} for {platform}'
    assert all('mismatches=0' in line for line in checks),f'Unexpected mismatches: {platform}'
    assert f'FULL_VGG11_RISCV_RESULT class={manifest["predicted_class"]}' in stdout,f'Prediction mismatch: {platform}'
    assert 'PASS: COMPLETE VGG11 inference and all module checks' in stdout
    logs.append(f'{platform.upper()} PASS {len(checks)}/{len(trace)} checks')

summary={
    'experiment':'Connected VGG11 portable-C full model inference',
    'network':'Mahdi VGG11 CIFAR10',
    'native_status':'PASS','riscv_qemu_status':'PASS',
    'reference_module_checks':len(trace),
    'predicted_class':int(manifest['predicted_class']),
    'true_label':int(manifest['reference_label']),
    'accuracy_assessed':int(manifest['reference_label'])>=0,
    'reference_input_count':1,
    'whole_model_riscv_software_inference':True,
    'whole_model_rocket_rtl_inference':False,
    'physical_fpga_tested':False,
    'source_sha256':checksum(sources/'vgg11.c'),
    'header_sha256':checksum(sources/'parameters.h'),
    'riscv_binary_sha256':checksum(out/'vgg11_riscv'),
}
(out/'results.json').write_text(json.dumps(summary,indent=2)+'\n')

# Preserve only small reproducing artifacts and plain text logs in Git.
evidence.mkdir(parents=True)
for fn in ('results.json','export_info.json','native.txt','qemu.txt','compile_native.txt','compile_riscv.txt'):
    shutil.copy2(out/fn,evidence/fn)
(evidence/'evidence.json').write_text(json.dumps({
    'reference_manifest_sha256':checksum(source/'manifest.json'),
    'source_sha256':checksum(sources/'vgg11.c'),
    'header_sha256':checksum(sources/'parameters.h'),
    'python_generator_sha256':checksum(sources/'vgg11_full_riscv.py'),
    'native_executable_sha256':checksum(out/'vgg11_native'),
    'riscv_executable_sha256':checksum(out/'vgg11_riscv'),
    'full_rocket_rtl':False,'physical_fpga':False,
},indent=2)+'\n')
print('\n'.join(logs),flush=True)
print('PASS: full-model VGG11 RISC-V QEMU with connected inference graph',flush=True)
print('Git source:',sources,flush=True)
print('Git evidence:',evidence,flush=True)
print('Local tensor data:',out,flush=True)
print('NOTE: one random reference input; no accuracy claim; no full-network RTL',flush=True)
