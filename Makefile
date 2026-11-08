# HEART unified entry points: preparation, validation, and documented reproduction gates.
# IMPORTANT: 'reproduce-software' and 'reproduce-hardware' intentionally do not claim
# end-to-end reproduction until their external checkpoints and board runs are validated.
SHELL := /bin/bash
.DEFAULT_GOAL := help
PYTHON ?= python
WORK_DIR ?=

.PHONY: help check verify-results verify-datasets reproduce-datasets \
        preflight-pubmed preflight-health preflight-pruning hardware-check \
        reproduce-software reproduce-hardware environment

help:
	@printf '%s\n' \
	  'HEART-ARC2027 reproducibility commands' \
	  '  make check                          Source syntax and safety unit tests (CPU only)' \
	  '  make verify-results                 Check recorded E1/E2/E3 summary CSV SHA-256' \
	  '  make verify-datasets WORK_DIR=...   Check five input dataset SHA-256 hashes' \
	  '  make reproduce-datasets WORK_DIR=... Rebuild V4 and PubMed splits in temporary workspace, check hashes' \
	  '  make preflight-pubmed WORK_DIR=...   Read-only PubMed training input check' \
	  '  make preflight-health WORK_DIR=...   Read-only health training input check' \
	  '  make preflight-pruning WORK_DIR=...  Read-only pruning input check' \
	  '  make hardware-check                 Rocket and SaCriS-V CLI help / import smoke check' \
	  '  make environment                    Print current Python and key dependency versions' \
	  '  make reproduce-software             Explain remaining complete-training blocker (exit 2)' \
	  '  make reproduce-hardware             Explain hardware-measurement blocker (exit 2)' \
	  '' \
	  'Select a Python interpreter using PYTHON=/path/to/python; nothing here submits Slurm jobs.'

check:
	$(PYTHON) -m unittest discover -s software/tests -p 'test_heart_paths.py' -v
	$(PYTHON) -m compileall -q software/scripts
	@for f in software/slurm/*.slurm software/slurm/*.sh; do bash -n "$$f" || exit 1; done
	@echo 'PASS: source checks (not GPU/FPGA execution)'

verify-results:
	$(PYTHON) software/scripts/verify_heart_inputs.py --mode results

verify-datasets:
	@test -n '$(WORK_DIR)' || { echo 'ERROR: supply WORK_DIR=/path/to/initialized/workspace' >&2; exit 2; }
	$(PYTHON) software/scripts/verify_heart_inputs.py --mode data --workspace '$(WORK_DIR)'

reproduce-datasets:
	@test -n '$(WORK_DIR)' || { echo 'ERROR: supply WORK_DIR=/path/to/initialized/workspace' >&2; exit 2; }
	$(PYTHON) software/scripts/verify_heart_inputs.py --mode rebuild --workspace '$(WORK_DIR)'

preflight-pubmed preflight-health preflight-pruning:
	@test -n '$(WORK_DIR)' || { echo 'ERROR: supply WORK_DIR=/path/to/initialized/workspace' >&2; exit 2; }
	env HEART_WORK_DIR='$(WORK_DIR)' $(PYTHON) software/scripts/preflight_training.py --stage '$(@:preflight-%=%)'

hardware-check:
	cd hardware && $(PYTHON) -m optimizer.unified_cli --processor rocket --help >/dev/null
	cd hardware && $(PYTHON) -m optimizer.unified_cli --processor sacris-v --help >/dev/null
	@echo 'PASS: both hardware CLIs display help; no RTL simulation, FPGA synthesis, or board validation performed'

environment:
	@$(PYTHON) -c 'import sys,importlib.metadata as md; print("Python:",sys.version.split()[0]); [(print(p, md.version(p) if p in {x.metadata.get("Name", "").lower() for x in md.distributions()} else "NOT INSTALLED")) for p in ("torch","transformers","peft","accelerate","datasets","bitsandbytes","pyyaml","sentencepiece")]'

reproduce-software:
	@echo 'NOT YET VERIFIED: end-to-end LoRA training, merging, baseline provenance, pruning and E1/E2/E3 inference are not fully reproduced.' >&2
	@echo 'Use: make reproduce-datasets WORK_DIR=...; make preflight-pubmed WORK_DIR=...; see software/REPRODUCE_TRAINING.md and software/REPRODUCE_E2.md' >&2
	@exit 2

reproduce-hardware:
	@echo 'NOT YET VERIFIED: hardware synthesis, Rocket FPGA programming, board measurements and measurement inputs require external toolchain and board.' >&2
	@echo 'Use: make hardware-check; see hardware/FPGA_RUNBOOK.md.' >&2
	@exit 2
