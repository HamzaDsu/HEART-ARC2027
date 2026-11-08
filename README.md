# HEART — HealthSLM Compression and RISC-V Architecture Optimization

Research companion repository for the HEART paper.

This repository has two top-level project directories:

- [`software/`](software/) — HealthSLM compression, calibration, evaluation code, curated experimental evidence (software author).
- [`hardware/`](hardware/) — RISC-V / Rocket / FPGA implementation and verification (hardware collaborator).

## Reproducibility and scope

The software and hardware results are separate evidence streams. The reported packed Q3_K_S model was quantized from the *unpruned* FP16 GGUF checkpoint; it is not a pruned-then-quantized artifact. The 1,000-case synthetic benchmark contains training-prompt overlap. The 90-case E3 challenge is provisional, with independent clinical review pending. All deployment claims are limited to measured artifacts and workloads.

Large model checkpoints, private reviewer keys, personal health data, credentials, temporary outputs, and unrelated research projects are excluded. Consult the corresponding section READMEs before publishing.

## Collaboration

Create independent branches or pull requests for work in `software/` and `hardware/`. Avoid committing large artifacts or modifying the other contributor's area without review.

## Double-blind review

Keep the remote GitHub repository **private** during double-blind submission unless the venue explicitly permits identifiable repositories. Before publishing or sharing reviewer access, scrub usernames, absolute cluster paths, and other identifying metadata from code and manifests.
