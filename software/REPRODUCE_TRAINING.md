# HEART training, merging, and pruning: portable execution draft

This is a **safety/portability refactor** of the supplied original scripts, not proof that training from scratch reproduces the published numerical results. The original LoRA settings, assistant-only loss logic, sensitivity scoring, and magnitude-pruning strategy formulas are unchanged. Dependencies, upstream data provenance, deterministic seeding, and the relationship between the named precompression baseline and the newly merged health model still need verification.

## Non-destructive setup

From the repository root on Willi:

```sh
cd /path/to/HEART-ARC2027
python software/scripts/init_workspace.py --root /path/to/a/new/heart-workspace
```

**Choose a brand-new directory**. This command rejects any nonempty path and creates a marker that every GPU job requires. Never initialize in your original `/scratch/hamzamuh/health-slm-experiments` directory. The scripts reject existing output directories and never run `rm -rf`. Avoid putting GGUF/large checkpoint files under Git.

Choose your own empty workspace. Replace `/path/to/a/new/heart-workspace` in the examples with its actual absolute path. Use the same job exports with `HEART_PYTHON` set to the Python interpreter containing Torch, Transformers, PEFT, datasets, bitsandbytes, PyYAML, and other required dependencies.

## Missing prerequisites: verify or supply these inputs

* **PubMed-domain train/eval JSONL:** `$HEART_WORK_DIR/data/processed/pubmed_domain_train.jsonl`, `pubmed_domain_eval.jsonl`. HEART includes the historical download and processing scripts and `data/pubmed_source_manifest.json`. Processing was **exactly reproduced** from the archived 10,000-record raw sample. Fresh upstream acquisition is not yet byte-for-byte reproducible because the historical HF dataset revision was not recorded. Provide the archived raw sample separately or locate/pin an equivalent upstream snapshot; do not infer that the current default dataset version is identical.
* **V4 train/eval JSONL:** `$HEART_WORK_DIR/data/final_health_coaching_v4/processed/health_coaching_train.jsonl` and `health_coaching_eval.jsonl`. The HEART repo has `scripts/build_health_coaching_dataset.py` and SHA256 verification; generate into the new workspace using its CLI, then validate hashes before using them.
* **Base model:** `TinyLlama/TinyLlama-1.1B-Chat-v1.0` is fetched through Hugging Face. Record the exact upstream revision and Python package versions; the historical scripts did not pin them.
* **Precompression baseline:** pruning YAML and Slurm use `HEART_BASELINE_MODEL`, default `$HEART_WORK_DIR/precompression_baseline_v4`. Confirm whether the merged health-coaching model is exactly the paper's baseline before pointing that variable at the new merged directory. Do not silently assume they match.
* **FP16 GGUF:** `build_safety_imatrix_v4.slurm` expects an existing GGUF (`HEART_GGUF_F16`); the conversion/export procedure is a separate missing reproduction step. Prefer the already verified `scripts/reproduce_e2.py` for E2.

## Verified PubMed input preparation (8 November 2026)

The original download log reports `casinca/PUBMED_title_abstracts_2019_baseline`, train split, streaming mode, 10,005 source records scanned and 10,000 written. Original source file: `data/raw/pubmed_sample_10k.jsonl`, SHA-256 `b28714eb7d5b854dafc192de7a01392976fd12bc8432939fb58f433c8b881e94`. The source dataset revision was **not recorded**, so this is a reproducible transformation from a preserved raw input, **not proof of a stable fresh download**.

From that archived raw sample, `build_pubmed_corpus.py --eval-size 0.02 --min-chars 200 --max-records 10000 --seed 42` yielded exactly 9,800 training and 200 evaluation examples. A new run produced both original hashes:

* `pubmed_domain_train.jsonl`: `052b428468c95d23143653cdfe99c38b4be1ce11736fa13a3d11fdc6098bb3c9`
* `pubmed_domain_eval.jsonl`: `1f834eec156ae235b5b2cd25980a37b720b60211e8b2cea2819695a28fec4a0f`

Place the archived raw file under `<workspace>/data/raw/` and the verified train/eval files under `<workspace>/data/processed/`, or generate them with the script into a **fresh destination**. `build_pubmed_corpus.py` refuses to overwrite existing files. Example commands, substituting your own absolute workspace path:

```sh
python software/scripts/build_pubmed_corpus.py \
  --input /path/to/a/new/heart-workspace/data/raw/pubmed_sample_10k.jsonl \
  --output-prefix /path/to/a/new/heart-workspace/data/pubmed_rebuilt/pubmed_domain \
  --eval-size 0.02 --min-chars 200 --max-records 10000 --seed 42
python software/scripts/verify_pubmed_data.py \
  --workspace /path/to/a/new/heart-workspace \
  --rebuilt-dir /path/to/a/new/heart-workspace/data/pubmed_rebuilt
```

The 13 MB text sample and 13 MB processed train file are not included in Git; check the dataset's license before redistributing. **Only provenance, script, and hashes are committed.**

## Read-only preflight

You can run the following using the Willi shell (`tcsh`); this requires PyYAML, but not a GPU. Use `env` so you do not need Bash `export` syntax.

```sh
env HEART_WORK_DIR=/path/to/a/new/heart-workspace python software/scripts/preflight_training.py --stage pubmed
env HEART_WORK_DIR=/path/to/a/new/heart-workspace python software/scripts/preflight_training.py --stage health
env HEART_WORK_DIR=/path/to/a/new/heart-workspace python software/scripts/preflight_training.py --stage pruning
```

If a prerequisite is missing or an output path is already present, the stage reports `NOT READY`. Do not submit a training job until the appropriate preflight reports `PREFLIGHT PASSED`.

## Slurm submission (only after preflight)

The scripts' `#SBATCH --output=logs/...` requires a `logs/` directory **in the submission working directory**. Submit from `software/` as follows:

```sh
cd /path/to/HEART-ARC2027/software
mkdir -p logs
sbatch --export=ALL,HEART_WORK_DIR=/path/to/a/new/heart-workspace,HEART_PYTHON=/path/to/python-with-ML-dependencies slurm/train_pubmed_lora_10k.slurm
```

After successful PubMed training, submit `merge_pubmed_lora.slurm`; then `train_health_coaching_v4_3ep.slurm`; then `merge_health_coaching_v4_3ep.slurm`. Only after confirming the reference precompression baseline, submit pruning (`prune_health_v4.slurm`, `prune_health_v4_lower30.slurm`) and the appropriate evaluation jobs.

Supply the appropriate Python interpreter through `HEART_PYTHON` when submitting. The repository does not hardcode a Willi interpreter. `HEART_LLAMA_DIR` is separately required for the GGUF jobs. Evaluation jobs with a model require `MODEL` and `NAME` exported via `sbatch --export`; these must refer to a model file and a short safe run name.

## File map

| File | Purpose |
|---|---|
| `scripts/download_pubmed_sample.py` | Obtain raw PubMed sample, with optional `--revision` and no overwrite |
| `scripts/build_pubmed_corpus.py` | Historical clean/shuffle/split, with no overwrite |
| `scripts/verify_pubmed_data.py` | Verify raw, train, eval SHA-256 hashes |
| `data/pubmed_source_manifest.json` | Upstream identity, historical counts, raw and processed hashes, provenance caveat |
| `scripts/heart_paths.py` | Resolve YAML variables; enforce marked, new output paths |
| `scripts/init_workspace.py` | Create a fresh workspace marker; refuses nonempty roots |
| `scripts/preflight_training.py` | Read-only training and pruning input checks |
| `slurm/heart_env.sh` | Common Slurm environment; no personal paths |
| `configs/*.yaml` | Original hyperparameters with variable data/model/output paths |
| `scripts/train_lora*.py` | Original training logic plus safe config/output preflight |
| `scripts/merge_lora.py` | Original LoRA merge plus fresh output check |
| `scripts/mix_match_prune_llm_assistant_only.py` | Original pruning logic plus fresh output check |

## Sanity checks before committing

Run from the root (CPU-only, no model or dataset needed):

```sh
python -m unittest discover -s software/tests -p 'test_heart_paths.py' -v
python -m compileall -q software/scripts
bash -n software/slurm/train_pubmed_lora_10k.slurm
```

These are syntax/workspace checks, **not** a GPU training reproduction test. The original training config does not set a random seed; full numerical reproducibility remains unproven until a pinned upstream source/model, dependency locks, seed policy, and end-to-end result checks have been completed.
