# Software — HealthSLM

This directory contains the software contribution to the HEART paper: model compression, safety-related calibration, test-set construction, model evaluation, and compact result summaries.

The script `import_from_willi.py` copies a **fixed allowlist** of paper-relevant files from the existing Willi HealthSLM project. It does not recursively copy `scripts/`, `results/`, model directories, or unrelated FUSION research. Review every file before publishing; existing Slurm scripts can contain identifiable `/home/hamzamuh` paths.

## Import on Willi

From the clean repository root:

```sh
python software/import_from_willi.py --source /home/hamzamuh/projects/health-slm-thesis --experiments /scratch/hamzamuh/health-slm-experiments
python software/import_from_willi.py --source /home/hamzamuh/projects/health-slm-thesis --experiments /scratch/hamzamuh/health-slm-experiments --apply
```

Without `--apply`, the importer only prints its plan. Existing destination files with different contents are never overwritten. The importer writes `software/FILE_MANIFEST.json` containing checksums of imported files.

## Suggested organization

- `scripts/` — training, quantization, benchmark construction, and evaluation scripts
- `slurm/` — small Slurm job descriptions
- `data/` — only review-approved, synthetic benchmark / calibration data
- `results/` — small summary tables and non-sensitive aggregate audits

## Critical experimental qualifications

- The final Q3_K_S model is quantized from an **unpruned** FP16 source.
- The designated 1,000-case synthetic evaluation has 430 prompts with exact normalized prompt overlap against training, including all 237 urgent evaluation cases.
- The 90-case E3 pilot contains 30 examples per label with no exact normalized prompt overlap; this **does not** prove template/semantic independence or clinical validity.
- Uniform-96 and safety-weighted-96 calibration use the same 48 distinct examples; weight repetition differs (2:2:2 versus 1:2:3).
- Safety-weighted GGUF exact SHA-256: `9d22702da628362269ff0e2847cfc55d5e65b6e217134f97e7026c7e1610fbb0`.
- Exact-label metrics and phrase-based refusal heuristics are **not** equivalent to clinical response-safety review.
- FP16/pruned Transformer inference and CPU llama.cpp GGUF inference have different backends; do not compare latency without isolated, matched conditions.
- The qualitative E3 emergency audit found important missed escalations. Independent response and reference-label reviews remain pending.

No trained model weights are committed. Keep clinical or sensitive records and reviewer identity mappings outside this repository. Verify publishing rights before committing calibration texts or original evaluation data.

## Recorded software revisions

- Source HealthSLM project before adding E3 scripts: `98a2098a4477d69f8c43f7a95b7927e6303c9835`.
- HealthSLM E3 scripts commit: `1fb9a4e`.
- llama.cpp: `a868c3e3c56657f7e8a6231190dbbe90e7dd86c0`.

Some copied Slurm scripts may contain Willi-specific absolute paths; they are archived as executed and require environment-specific path adaptation on other machines.

## Further files to add by manual review

The default allowlist covers confirmed evaluation and calibration code. Add **only the actual scripts used** for biomedical adaptation, Health Coaching V4 instruction tuning, FP16 GGUF conversion, and the 11.55% / 20.35% pruning recipes once their names and dependencies have been verified. Do not claim end-to-end regeneration of trained weights until that provenance is included.
