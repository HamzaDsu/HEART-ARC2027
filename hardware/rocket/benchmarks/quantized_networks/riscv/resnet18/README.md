# Full ResNet18 RISC-V Inference

Mahdi's ResNet18 GTSRB model was executed using portable C
on native Linux and RISC-V Linux QEMU.

## Verified
- Full model execution: PASS on both platforms
- 50/50 reference module checks passed
- Final predicted class: 25
- One deterministic reference input

## Reproducibility
The Python generator and original launcher are preserved here.
They use the original local model artifact layout.

Required external artifacts:
- resnet18-c-export-20261029
- resnet18-full-reference-20261029

These must be restored under ~/health-slm-model-validation.
The generator may refuse to overwrite existing output directories.

Large tensor binaries and compiled executables are excluded
from Git. Executable checksums are recorded in evidence.json.

## Limitations
Full-network Rocket RTL inference and physical FPGA execution
have not been performed.
