# HEART: Safety-Aware HealthSLM Compression and RISC-V ArchiTecture Optimization

**Research companion repository for the HEART manuscript (ARC 2027).**

HEART investigates how a domain-specific small language model (SLM) can be compressed for a resource-constrained application while preserving measurable response-policy behavior, and how the resulting model representation can inform the selection of a configurable RISC-V processor architecture. The work connects **safety-aware HealthSLM compression** with **model-aware RISC-V design-space exploration**.

The two stages are connected by the selected model's **GGUF metadata, memory requirements, and representative quantized computation**. Model evaluation, processor simulation, FPGA implementation, and architecture estimates remain distinct sources of evidence.

## Research overview

### 1. Safety-aware HealthSLM preparation and compression

The software workflow adapts **TinyLlama-1.1B-Chat-v1.0** to a health-coaching response format:

1. **Biomedical adaptation:** LoRA training on PubMed-derived biomedical text.
2. **Health-coaching instruction tuning:** adaptation using Health Coaching V4, with structured recommendations, response-policy labels, and rationales.
3. **Compression analysis:** evaluation of unstructured pruning, simulated low-precision representations, and physically packed GGUF quantization.
4. **Safety-aware calibration:** category-weighted importance-matrix calibration before quantization, emphasizing urgent and medical-boundary scenarios.
5. **Response-policy evaluation:** measurement of strict label adherence, urgent escalation recall, medical-boundary behavior, output-format compliance, and continuation errors.

The manuscript's selected packed artifact is **Q3_K_S GGUF**, generated from the **unpruned** health-coaching FP16 checkpoint. Its reported serialized size is **499.34 MB**, a **76.24% reduction** from the FP16 reference. The model is evaluated on a designated **1,000-example synthetic benchmark**; those results measure behavior under that protocol and do not constitute clinical validation.

The target response-policy categories are:

- `safe_lifestyle_advice`
- `urgent_medical_escalation`
- `medical_boundary_refusal`

For dataset preparation, training, quantization, and evaluation entry points, see [`software/README.md`](software/README.md), [`software/REPRODUCE_TRAINING.md`](software/REPRODUCE_TRAINING.md), and the repository-wide [`REPRODUCE.md`](REPRODUCE.md).

### 2. Model-aware RISC-V architecture optimization

The hardware workflow uses characteristics of the selected quantized representation to guide processor selection:

1. **Characterize the model:** inspect GGUF tensor metadata, quantization format, weight storage, KV-cache assumptions, and runtime memory requirements.
2. **Describe representative computation:** use quantized **Q3_K × Q8_K** dot-product workloads to study decoding-related arithmetic and memory behavior.
3. **Explore architectures:** vary Rocket processor count, instruction/data cache sizes, scratchpad options, L2-cache settings, and clock targets.
4. **Screen candidates:** consider accessible memory and FPGA LUT, flip-flop, BRAM, and DSP budgets.
5. **Rank feasible designs:** apply Pareto filtering and the **Balanced**, **Performance**, or **Resources** objective.
6. **Validate at the appropriate level:** use Verilator for specified kernel workloads and Vivado for FPGA implementation resources and timing.

The paper evaluates three Rocket configurations on the **Digilent Nexys Video** board (Artix-7 **XC7A200T**):

| Objective | Rocket cores | Instruction / data L1 caches | Target clock |
| --- | ---: | --- | ---: |
| Balanced | 1 | 16 KiB / 16 KiB | 50 MHz |
| Performance | 2 | 32 KiB / 32 KiB | 50 MHz |
| Resources | 1 | 8 KiB / 8 KiB | 25 MHz |

These are **configuration targets and implementation evaluations**, not measurements of full HealthSLM inference throughput on FPGA. The reported quantized-kernel cycle counts likewise describe the particular simulated workloads, not complete language-model execution.

The hardware directory additionally contains **SaCriS-V** RTL, simulation, and synthesis material and a shared optimizer interface. This is supplementary repository tooling; the uploaded manuscript's main architecture evaluation focuses on **Rocket/Chipyard**, and its Rocket measurements should not be attributed to SaCriS-V.

## Repository layout

```text
HEART-ARC2027/
├── software/                    HealthSLM preparation, compression and evaluation
│   ├── README.md
│   ├── REPRODUCE_TRAINING.md
│   ├── scripts/
│   ├── configs/
│   ├── slurm/
│   └── tests/
├── hardware/                    RISC-V design exploration and FPGA workflow
│   ├── README.md
│   ├── FPGA_RUNBOOK.md
│   ├── rocket/                   Rocket/Chipyard configurations and workloads
│   ├── sacris-v/                 Additional SaCriS-V RTL and synthesis sources
│   ├── optimizer/                Hardware optimizer and unified CLI
│   └── scripts/                  FPGA environment and UART-TSI helpers
├── Makefile                     Repository workflow entry points
├── REPRODUCE.md                 Cross-project reproduction guide
└── README.md
```

## Getting started

Clone the repository and begin with the reproduction guide:

```bash
git clone <repository-url>
cd HEART-ARC2027
```

Read [`REPRODUCE.md`](REPRODUCE.md) for the supported repository-level workflow and input checks. Environment setup differs between GPU-based model preparation and RISC-V/FPGA hardware experiments.

### Software workflow

See [`software/README.md`](software/README.md) for the software project map and [`software/REPRODUCE_TRAINING.md`](software/REPRODUCE_TRAINING.md) for training preparation. The software workflow uses Python, PyTorch, Transformers/PEFT, and llama.cpp; reproducing the reported training runs requires appropriate compute resources, dependencies, data preparation, and matching model revisions.

### Hardware optimizer

The unified hardware CLI provides separate Rocket and SaCriS-V entry points. To inspect the supported arguments:

```bash
cd hardware
python3 -m optimizer.unified_cli --processor rocket --help
python3 -m optimizer.unified_cli --processor sacris-v --help
```

These commands display the interface; they do **not** reproduce the paper's architecture selection by themselves. Measured-data selection may require regenerating the corresponding workload and implementation inputs.

### FPGA simulation and execution

Use [`hardware/README.md`](hardware/README.md) for hardware setup and source locations, and [`hardware/FPGA_RUNBOOK.md`](hardware/FPGA_RUNBOOK.md) for the detailed FPGA procedure: environment checks, Chipyard configuration, RTL generation, simulation, Vivado implementation, programming, and UART-TSI workload loading.

The documented target platform is **Nexys Video / Artix-7 XC7A200T**. The original workflow used Chipyard, a RISC-V bare-metal toolchain, Verilator, **Vivado 2022.2**, and `openFPGALoader`. Tool and board revisions must be matched to the intended build configuration.

## Evidence and interpretation

The manuscript separates evidence into four categories:

| Evidence | What it establishes |
| --- | --- |
| **Software measurements (S)** | Serialized model size, response-policy metrics, and CPU inference results |
| **Verilator measurements (V)** | Cycle counts and checksums for particular quantized RISC-V kernels |
| **FPGA implementation (I)** | Vivado resource utilization, placement/routing, and timing for specific Rocket designs |
| **Optimizer estimates (E)** | Memory-feasibility assessments, resource/performance proxies, and ranked candidates |

In particular, successful FPGA implementation and simulated quantized-kernel execution **do not establish end-to-end HealthSLM inference on the physical board**. The synthetic health-coaching evaluation also does not establish clinical correctness or safety in untested settings. Refer to the paper for the full methods, results, assumptions, and limitations.

## Reproducibility and research use

The repository supplies research source code, configuration files, and reproduction instructions. Some workflows require external model artifacts, datasets, toolchains, or regenerated measurement inputs. Follow the relevant component guide and record model identifiers, hardware configurations, software revisions, and workload settings when reproducing an experiment.

For research reuse, retain applicable upstream copyright notices and licenses for third-party components. The HealthSLM outputs are **research artifacts, not medical advice or a clinically validated decision-support system**.

## Manuscript

**HEART: Safety-Aware HealthSLM Compression and RISC-V ArchiTecture Optimization** — ARC 2027 research manuscript.

The manuscript provides the complete experimental methodology, numerical comparisons, and discussion. This repository is its implementation and reproducibility companion.
