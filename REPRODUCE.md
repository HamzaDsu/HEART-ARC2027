# HEART-ARC2027 — Unified workflow status

This Makefile exposes *working checks* and intentionally does **not** claim that all paper experiments can already be reproduced from a fresh clone. The repository contains both contributors' source code; large weights, PubMed raw source, FPGA build outputs, measurements, and board dependencies are external.

## Verified and immediately usable commands

From the root:

```sh
make help
make check
make verify-results
make verify-datasets WORK_DIR=/scratch/hamzamuh/HEART-ARC2027-reproduction
make reproduce-datasets WORK_DIR=/scratch/hamzamuh/HEART-ARC2027-reproduction
make preflight-pubmed WORK_DIR=/scratch/hamzamuh/HEART-ARC2027-reproduction
make hardware-check
```

Replace `WORK_DIR` with the location of a workspace created by `software/scripts/init_workspace.py`. All `make` invocations are compatible with a `tcsh` login shell because GNU Make executes their recipes via `/bin/bash`. Supply `PYTHON=/scratch/.../envs/health-slm/bin/python` or another suitable interpreter if needed.

- `make check` runs workspace tests, Python syntax checks, and Bash syntax checks; no GPU or FPGA is used.
- `make verify-results` checks the recorded E1/E2/E3 summary CSV's published SHA-256; it **does not** recalculate model predictions or performance.
- `make verify-datasets` verifies archived input SHA-256 values in a marked workspace.
- `make reproduce-datasets` first verifies the archived PubMed/V4 inputs, rebuilds the PubMed 9,800/200 split and V4 8,000/1,000 dataset in a *unique temporary directory*, checks exact SHA-256 matches and cleans up only its newly generated temporary files. It neither downloads source data nor trains a model. The raw Hugging Face dataset revision used originally remains unpinned.
- `make preflight-health` and `make preflight-pruning` currently report missing models in the **new** workspace, which is expected until the PubMed adapter has been trained/merged and the correct HealthSLM V4 baseline has been established. Do not bypass the missing-model checks.
- `make hardware-check` runs both documented Rocket and SaCriS-V CLI help commands; it is only a command-import smoke check.
- `make reproduce-software` and `make reproduce-hardware` intentionally exit with an explicit blocked status until a full execution recipe can be validated. A successful CPU-only test is not proof of reproduced GPU/FPGA measurements.

## Full software pipeline (still requiring evidence)

1. Verify PubMed and V4 datasets with the SHA-256 commands above.
2. Record the exact TinyLlama base-model revision, GPU and Python dependency state and run PubMed LoRA training using `software/REPRODUCE_TRAINING.md`, isolated Slurm jobs, and fresh outputs.
3. Merge PubMed LoRA, train HealthSLM V4 and merge the V4 adapter. Establish that the resulting merged model corresponds to the paper's `precompression_baseline_v4` before using it for pruning.
4. Run conservative/lower30 pruning and E1/E2/E3 model evaluations with explicit checkpoint identifiers. For E2, use `software/REPRODUCE_E2.md`; the three Q3_K_S model variants were confirmed identical at tensor level (201 tensors each), but whole-file GGUF hashes may differ due to file path metadata.
5. Recalculate metrics and compare with the recorded CSV; this **has not been implemented** as a single end-to-end target.

The E2 GGUF models were quantized from the **unpruned FP16** source, not pruned-then-quantized. The 1,000-item V4 evaluation has training-prompt overlap. Clinical response review of the E3 challenge is still outstanding.

## Hardware workflow (requires collaborator)

See `hardware/FPGA_RUNBOOK.md` and `hardware/README.md`. Run `make hardware-check` to confirm CLI startup. For Rocket and SaCriS-V objective searches, provide the correct tool versions, board constraints, model inputs, and measured optimizer evidence. Full RTL generation, synthesis, FPGA bitstream creation, UART-TSI execution and physical measurements require the board toolchain and hardware contributor's documented invocations. The repository does **not** yet reproduce hardware measured figures automatically; SaCriS-V physical FPGA deployment is not claimed.

## Collaboration and publication safety

Do not commit model weights, personal/sensitive data, private reviewer material, large source datasets without redistribution permission, or Vivado generated artifacts. Keep the remote private for double-blind review, and review historical source paths and identifying comments before any public release.
