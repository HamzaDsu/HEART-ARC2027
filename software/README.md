# Software — HealthSLM

**HEART | Software research artifact for ARC 2027**

This directory contains the **HealthSLM software contribution** to HEART: PubMed-domain adaptation of TinyLlama, safety-oriented health-coaching instruction tuning, pruning experiments, importance-matrix-guided GGUF quantization, and E1/E2/E3 evaluation code and evidence.

The goal is to make the methodology inspectable and the verified stages reproducible without redistributing multi-gigabyte model checkpoints.

**Reproducibility boundary:** The V4 and PubMed dataset transformations and all three E2 Q3_K_S model variants have been verified against original artifacts. However, a fresh-clone, end-to-end run of LoRA training, merging, pruning, FP16 GGUF export, and every E1/E2/E3 evaluation is **not yet validated**. A recorded result file passing a checksum check is not an independent evaluation rerun.

**Start here:** [Unified reproduction guide](../REPRODUCE.md) · [Training and pruning guide](REPRODUCE_TRAINING.md) · [E2 quantization guide](REPRODUCE_E2.md) · [Results](results/) · [Hardware counterpart](../hardware/README.md)

## 1. Repository layout

```text
software/
├── README.md                         # This guide
├── REPRODUCE_TRAINING.md             # LoRA, merging and pruning workflow
├── REPRODUCE_E2.md                   # E2 quantization and verification
├── PORTABILITY_AUDIT.md             # Path and safety refactoring notes
├── scripts/
│   ├── build_health_coaching_dataset.py
│   ├── download_pubmed_sample.py
│   ├── build_pubmed_corpus.py
│   ├── verify_pubmed_data.py
│   ├── train_lora.py
│   ├── train_lora_assistant_only.py
│   ├── merge_lora.py
│   ├── mix_match_prune_llm_assistant_only.py
│   ├── reproduce_e2.py
│   ├── compare_gguf.py
│   ├── evaluate_precompression_baseline.py
│   ├── evaluate_gguf_safety.py
│   ├── build_e3_challenge.py
│   ├── heart_paths.py
│   ├── init_workspace.py
│   ├── preflight_training.py
│   └── verify_heart_inputs.py
├── configs/                          # Training and pruning YAML configurations
├── slurm/                            # Slurm jobs and environment setup
├── data/
│   ├── calibration/                  # Uniform and safety calibration inputs
│   ├── health_coaching_e3.jsonl      # Provisional E3 challenge dataset
│   └── pubmed_source_manifest.json  # PubMed source and hash provenance
├── results/
│   ├── e1_e2_e3_master_results.csv   # Recorded experiment summaries
│   ├── e2_reference_hashes.json      # Reference E2 artifact checksums
│   └── e3_contamination_audit.json   # E3 overlap audit
├── tests/                            # Workspace and path safety tests
└── import_from_willi.py              # Historical migration utility
```

Model weights, raw PubMed abstracts, large generated GGUF files, private review materials, and GPU experiment outputs are **not distributed** in this directory.

## 2. Research methodology and experimental pipeline

The HealthSLM software contribution is organized into six stages.

| Stage | Method | Reproducibility status |
| --- | --- | --- |
| **Data preparation** | PubMed corpus processing and synthetic Health Coaching V4 dataset generation | Exact dataset hashes verified |
| **Biomedical adaptation** | TinyLlama-1.1B-Chat-v1.0 with PubMed LoRA | Training code and inputs available; fresh training pending |
| **Health instruction tuning** | Assistant-only training on Health Coaching V4 and adapter merging | Source and configurations available; independent training pending |
| **E1: Compression analysis** | FP16 baseline and pruning experiments | Original checkpoints identified; evaluation reproduction pending |
| **E2: GGUF quantization** | Q3_K_S without imatrix, uniform calibration, and safety-weighted calibration | All three quantized models verified at the tensor level |
| **E3: Safety challenge** | Evaluation on a 90-case synthetic challenge set | Dataset and recorded results available; independent review pending |

### Model lineage

The software starts with **TinyLlama-1.1B-Chat-v1.0**, performs biomedical adaptation using PubMed-derived text, and then applies health-coaching instruction tuning.

The resulting merged HealthSLM V4 checkpoint forms the common precompression baseline.

From this checkpoint, the experiments follow two distinct branches:

1. **Pruning:** evaluate selected pruning configurations and their response-policy behavior.
2. **GGUF quantization:** convert the unpruned FP16 checkpoint to GGUF and apply Q3_K_S quantization with different importance-matrix calibration conditions.

**Important:** The final Q3_K_S GGUF model is quantized from the **unpruned FP16 model**. It is not a pruned-then-quantized artifact.

The two branches must therefore be interpreted separately.

## 3. Requirements and environment

The software workflow requires the following dependencies.

| Dependency | Purpose |
| --- | --- |
| Python 3.11 | Dataset processing, training and evaluation |
| PyTorch | Model training and inference |
| Transformers | Model/tokenizer loading and training |
| PEFT | LoRA adaptation and merging |
| Accelerate | Training execution |
| Hugging Face datasets | PubMed source loading |
| bitsandbytes | Low-precision model operations |
| PyYAML and sentencepiece | Configuration and tokenizer support |
| llama.cpp | Importance-matrix generation, GGUF quantization and inference |
| Slurm | Resource-managed execution on supported clusters |
| CUDA-capable GPU | Transformer training and applicable evaluation jobs |

The original E2 experiments used llama.cpp revision:

```text
a868c3e3c56657f7e8a6231190dbbe90e7dd86c0
```

The verified Willi environment included:

| Package | Observed version |
| --- | --- |
| PyTorch | 2.11.0+cu128 |
| Transformers | 4.57.6 |
| PEFT | 0.19.1 |
| Accelerate | 1.14.0 |
| datasets | 5.0.0 |
| bitsandbytes | 0.49.2 |
| PyYAML | 6.0.3 |
| sentencepiece | 0.2.1 |

These are recorded environment versions, not proof that the historical training runs used identical dependency versions.

Researchers should record the base-model revision, dependencies, GPU model, CUDA version, llama.cpp revision, and experiment configuration for each reproduction run.

## 4. Quick start: repository checks

The unified HEART repository provides a root Makefile.

From the **repository root**, run:

```bash
make help
make check
make verify-results
make hardware-check
```

The commands perform the following checks:

| Command | Purpose |
| --- | --- |
| `make check` | Run workspace safety tests and Python syntax checks |
| `make verify-results` | Verify the stored E1/E2/E3 result CSV checksum |
| `make hardware-check` | Verify Rocket and SaCriS-V optimizer CLI startup |
| `make verify-datasets` | Verify dataset checksums in a populated workspace |
| `make reproduce-datasets` | Regenerate and compare documented dataset transformations |

These checks do **not** execute full GPU training, FPGA synthesis, physical board deployment, or all model evaluations.

The `make reproduce-software` target is not yet a fully validated end-to-end execution target.

See [REPRODUCE.md](../REPRODUCE.md) for the unified software and hardware reproduction workflow.

## 5. Dataset preparation and reproducibility

### 5.1 Health Coaching V4 dataset

The synthetic Health Coaching V4 dataset contains:

| Dataset | Examples |
| --- | ---: |
| Training | 8,000 |
| Evaluation | 1,000 |

The three target response-policy labels are:

- `safe_lifestyle_advice`
- `urgent_medical_escalation`
- `medical_boundary_refusal`

The dataset is generated through:

```bash
python software/scripts/build_health_coaching_dataset.py \
  --out-dir /absolute/path/to/workspace/data/final_health_coaching_v4/processed \
  --train-size 8000 \
  --eval-size 1000 \
  --seed 42
```

The generator reproduced the original training and evaluation files with exact SHA-256 matches.

### 5.2 PubMed biomedical adaptation dataset

The original PubMed pipeline used the Hugging Face dataset:

```text
casinca/PUBMED_title_abstracts_2019_baseline
```

The source was streamed from the training split.

The archived raw sample contains **10,000 PubMed records**, processed into:

| Dataset | Records |
| --- | ---: |
| Training | 9,800 |
| Evaluation | 200 |

The processing configuration uses an evaluation fraction of `0.02`, a minimum text length of 200 characters, and random seed `42`.

The original preparation scripts are:

```text
scripts/download_pubmed_sample.py
scripts/build_pubmed_corpus.py
scripts/verify_pubmed_data.py
```

The original raw sample was independently processed again, and both rebuilt train/evaluation datasets matched the historical SHA-256 hashes exactly.

**Limitation:** The historical Hugging Face dataset revision was not pinned. Dataset processing is reproducible from the archived raw sample, but a fresh upstream download is not guaranteed to contain identical records.

See [PubMed source manifest](data/pubmed_source_manifest.json) for provenance and expected checksums.

### 5.3 Reproduction workspace

Create a separate workspace before running experiments:

```bash
python software/scripts/init_workspace.py \
  --root /absolute/path/to/new-heart-workspace
```

After placing the documented inputs in the workspace, run:

```bash
make verify-datasets WORK_DIR=/absolute/path/to/new-heart-workspace
```

To independently regenerate the documented dataset transformations:

```bash
make reproduce-datasets WORK_DIR=/absolute/path/to/new-heart-workspace
```

Do not initialize or run reproduction jobs inside directories containing the original experimental checkpoints.

## 6. LoRA training, merging and pruning

The intended training sequence is:

1. PubMed biomedical LoRA adaptation.
2. Merge the PubMed LoRA adapter into the base model.
3. Health Coaching V4 assistant-only instruction tuning.
4. Merge the Health Coaching V4 adapter.
5. Produce the precompression baseline.
6. Apply and evaluate the selected pruning configurations.

The implementation is provided through:

```text
scripts/train_lora.py
scripts/train_lora_assistant_only.py
scripts/merge_lora.py
scripts/mix_match_prune_llm_assistant_only.py
```

Corresponding experiment configurations are stored in `configs/`, and Slurm launchers are stored in `slurm/`.

### Training preflight

Before launching GPU jobs, verify the input paths and output locations:

```bash
make preflight-pubmed WORK_DIR=/absolute/path/to/new-heart-workspace
make preflight-health WORK_DIR=/absolute/path/to/new-heart-workspace
make preflight-pruning WORK_DIR=/absolute/path/to/new-heart-workspace
```

Health instruction tuning requires the merged PubMed checkpoint.

Pruning requires the merged Health Coaching V4 checkpoint, referred to as the **precompression baseline**.

The original Willi artifacts and their checkpoint relationships have been identified. The pruning input preflight was successfully validated against the original V4 baseline.

**Current validation boundary:** These checks verify prerequisites and output locations. They do not prove that a fresh training run will reproduce the original model weights or reported metrics.

Portable Slurm launchers support configurable environment variables, including:

```text
HEART_WORK_DIR
HEART_PYTHON
HEART_BASELINE_MODEL
HEART_LLAMA_DIR
HEART_GGUF_F16
```

The portable workflow protects existing outputs against accidental overwriting.

For detailed commands and experiment configurations, see [REPRODUCE_TRAINING.md](REPRODUCE_TRAINING.md).

## 7. Safety-aware GGUF quantization (E2)

The E2 experiment evaluates three Q3_K_S quantization configurations using the same unpruned FP16 GGUF source.

| Variant | Calibration strategy |
| --- | --- |
| No imatrix | Direct FP16-to-Q3_K_S quantization |
| Uniform-96 | Balanced importance-matrix calibration |
| Safety-weighted-96 | Greater calibration emphasis on urgent and medical-boundary scenarios |

Uniform and safety-weighted calibration use the same **48 distinct calibration examples**, repeated to produce 96 effective instances.

The category repetitions differ:

- Uniform: `2:2:2`
- Safety-weighted: `1:2:3`

The categories are lifestyle advice, medical-boundary refusal, and urgent medical escalation, respectively.

### Reproduction command

A preview can be generated with:

```bash
python software/scripts/reproduce_e2.py \
  --llama-dir /absolute/path/to/llama.cpp \
  --fp16 /absolute/path/to/health-slm-v4-f16.gguf \
  --output-dir /absolute/path/to/new-e2-output \
  --mode uniform96 \
  --phase all
```

The script uses preview mode by default.

Add `--execute` only when the required resources and output directory are ready.

### Verified quantization results

All three quantized configurations were regenerated using the original FP16 GGUF input.

| Configuration | Verification |
| --- | --- |
| No imatrix | Exact complete-file SHA-256 match |
| Uniform-96 | 201/201 quantized tensor payloads identical |
| Safety-weighted-96 | 201/201 quantized tensor payloads identical |
| Independently regenerated safety imatrix | 308/308 tensor payloads identical |

Differences in complete-file hashes for calibrated models were traced to embedded source-path provenance metadata.

The GGUF comparator confirmed that tensor bytes, tensor layouts, and non-provenance metadata matched the original artifacts.

The selected Q3_K_S model has a serialized size of approximately **499.34 MB**, compared with approximately **2,201.02 MB** for its FP16 GGUF source, representing a **77.31% reduction in serialized size** for these specific files.

This measurement describes model storage, not clinical safety or inference accuracy.

For the full verification procedure and reference checksums, see [REPRODUCE_E2.md](REPRODUCE_E2.md).

## 8. Response-policy evaluation and limitations

The software includes evaluation methods for:

- Strict response-policy label accuracy.
- Urgent medical escalation behavior.
- Medical-boundary refusal behavior.
- Output-format compliance.
- Continuation errors.
- Model compression and serialized size.
- Selected inference/runtime measurements.

### E1: Baseline and pruning evaluation

E1 evaluates the original FP16 HealthSLM checkpoint and selected pruning configurations.

The source repository includes model-evaluation and pruning scripts, but independent regeneration of every E1 numerical result remains pending.

### E2: Quantization evaluation

E2 compares the three Q3_K_S conditions under the designated Health Coaching V4 evaluation protocol.

The quantized model weights have been reproduced, but tensor equality alone does not establish that the reported inference metrics will reproduce under a different runtime or evaluation environment.

### E3: Safety challenge evaluation

E3 is a provisional synthetic challenge set containing **90 examples**, divided equally across the three response-policy categories.

The recorded audit found no exact normalized prompt overlap with the training set. However, this does not establish semantic independence, template-level independence, clinical realism, or clinical validity.

### Important research qualifications

The following limitations must be considered when interpreting the software results.

**Training/evaluation overlap**

The designated 1,000-example V4 evaluation set contains **430 prompts with exact normalized-prompt overlap** against training, including all 237 urgent evaluation prompts.

Consequently, results from this evaluation should not be described as fully independent generalization measurements.

**Clinical response safety**

Exact safety-label accuracy, keyword or phrase matching, and output-format checks do not substitute for clinical expert evaluation.

The E3 qualitative review identified important missed-escalation cases. Independent reference-label review and response-safety assessment remain outstanding.

**Runtime differences**

FP16/pruned Transformer inference and llama.cpp GGUF inference use different inference backends.

Performance comparisons require carefully matched hardware, runtime settings, model workloads, and measurement procedures.

**Recorded versus reproduced results**

The repository includes `e1_e2_e3_master_results.csv`.

Its SHA-256 checksum has been verified, but this establishes the integrity of the recorded results file—not an independent reproduction of all experimental predictions or metrics.

The HealthSLM system is a **research prototype, not a clinically validated medical decision-support system**.

## 9. Reproducibility checklist

### Verified

- [x] Regenerate the 8,000-example V4 training dataset.
- [x] Regenerate the 1,000-example V4 evaluation dataset.
- [x] Match original V4 dataset SHA-256 hashes.
- [x] Rebuild the PubMed training/evaluation split from the archived raw sample.
- [x] Match original PubMed dataset SHA-256 hashes.
- [x] Validate workspace initialization and path-safety tests.
- [x] Verify PubMed training preflight inputs.
- [x] Verify V4 pruning input preflight using the original baseline.
- [x] Reproduce all three E2 Q3_K_S model tensor payloads.
- [x] Independently regenerate the safety importance matrix.
- [x] Verify recorded E1/E2/E3 results-file checksum.
- [x] Validate root Makefile software and hardware CLI checks.

### Pending

- [ ] Recover or pin the original upstream PubMed dataset revision.
- [ ] Record the exact TinyLlama base-model revision used for training.
- [ ] Independently reproduce the PubMed LoRA training results.
- [ ] Independently reproduce Health Coaching V4 fine-tuning and merging.
- [ ] Verify original pruning configurations and resulting checkpoint metrics.
- [ ] Independently reproduce the FP16 GGUF export step.
- [ ] Rerun E1/E2/E3 model inference and recompute metrics.
- [ ] Complete blinded E3 reference-label and response-safety review.
- [ ] Reconcile paper metrics with verified artifacts and experiment settings.
- [ ] Verify release permissions and third-party licensing.

**Current release boundary:** The software repository supports verified dataset processing, E2 quantization, source-code checks, and prerequisite validation. A complete fresh-clone reproduction from training through all paper evaluations has not yet been demonstrated.

## 10. Provenance and contributor guidance

### Recorded software revisions

| Component | Revision |
| --- | --- |
| Original HealthSLM source snapshot | `98a2098a4477d69f8c43f7a95b7927e6303c9835` |
| E3 source addition | `1fb9a4e` |
| llama.cpp E2 revision | `a868c3e3c56657f7e8a6231190dbbe90e7dd86c0` |

The script `import_from_willi.py` is retained as a historical, allowlisted migration utility. It is **not** part of the normal reproduction workflow and should not be used to overwrite reviewed repository files.

### Research data and publication guidance

Keep contributions limited to paper-relevant model preparation, compression, calibration, evaluation, configurations, verification tools, and small non-sensitive result summaries.

Do not commit:

- Multi-gigabyte model weights or checkpoints.
- Raw PubMed text without appropriate redistribution rights.
- Patient records or confidential health information.
- Credentials, personal access tokens, or private infrastructure configuration.
- Reviewer identities or confidential annotations.
- Large training logs and generated intermediate artifacts.

Preserve applicable upstream licenses, attribution, and source provenance.

Because this repository is publicly accessible, review source files and Git history for personal paths, identifiable information, third-party licensing obligations, and materials that should not be distributed.

For anonymous conference review, follow the venue's artifact and anonymity requirements before referencing an identifiable repository in submission materials.

Coordinate software documentation, model-artifact references, and numerical results with the hardware contributor so that the root README and the paper remain consistent.

---

**HEART: Safety-Aware HealthSLM Compression and RISC-V ArchiTecture Optimization**

This software implementation accompanies the ARC 2027 research manuscript and provides the HealthSLM preparation, compression, and evaluation component of the joint software/hardware study.
