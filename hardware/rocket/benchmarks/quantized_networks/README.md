# Automated Quantized Network Benchmarks

Reproducible CPU reference benchmarks for four quantized networks:

- ResNet18 (GTSRB)
- VGG11-BN (CIFAR-10)
- Swin-Tiny (CIFAR-100)
- LeViT-384 (CIFAR-10)

Original model packages are stored in archives/ using Git LFS.

## Requirements

Python 3.11, PyTorch, TorchVision, NumPy, timm, protobuf.

Tested on WSL Ubuntu with CPU-only PyTorch.

## Environment setup

Run from the repository root:

    conda create -n quantized-network-benchmarks python=3.11 pip -y
    conda activate quantized-network-benchmarks

    python -m pip install torch==2.14.1+cpu torchvision==0.29.1+cpu --index-url https://download.pytorch.org/whl/cpu
    python -m pip install -r benchmarks/quantized_networks/requirements.txt

## Download network archives

    git lfs pull

## Verify original archives

    cd benchmarks/quantized_networks/archives
    sha256sum -c SHA256SUMS
    cd ../../..

## Prepare all networks automatically

    python benchmarks/quantized_networks/prepare_models.py

## Run all benchmarks automatically

    python benchmarks/quantized_networks/run_benchmarks.py

## Results

Results are saved under:

    ~/quantized-network-benchmarks/results/

Including benchmark_results.csv, archive_manifest.json and logs.

## Important notes

- These benchmarks use the supplied Python integer-inference implementations.
- They run on CPU, not RISC-V hardware or FPGA.
- Timing includes Python startup, model loading and inference.
- The supplied VGG11 dataset archive is incomplete. The preparation
  script disables automatic dataset downloading and the smoke test
  uses a random input.
- Successful smoke tests do not establish classification accuracy.
- The existing HealthSLM GGUF-to-C automation remains independent.

## Reference benchmark measurements

See `reference_results.csv` for the verified four-network
CPU benchmark results.

These measurements were collected on the development WSL
environment using the automated benchmark runner.

Runtime represents complete process execution, including model
loading. Memory represents maximum process resident memory.

Results are CPU reference measurements, not FPGA or RISC-V
hardware performance measurements.

## Automated weight-memory analysis

Run from the repository root:

    python benchmarks/quantized_networks/analyze_memory.py

The script compares configured mixed-precision weight storage
against uniform 8-bit weight storage using checkpoint tensor
dimensions and per-output-channel bit-width assignments.

Results are saved to:

    ~/quantized-network-benchmarks/results/weight_memory_analysis.csv

Verified results are included in reference_memory_results.csv.

Important limitations:

- Storage estimates assume ideal bit packing.
- Estimates cover successfully matched weight tensors only.
- Quantization metadata, packing overhead, activations, runtime
  buffers and other model components are excluded.
- Results are not measured FPGA memory or resource utilization.
- LeViT384 has two unmatched ImageNet classifier configurations:
  head.linear and head_dist.linear.
