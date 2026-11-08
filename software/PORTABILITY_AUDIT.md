# Review of the source archive (8 November 2026)

Original source files are identified by SHA-256 in `PORTABILITY_SOURCE_HASHES.json`, calculated from the user's uploaded `heart-training-review.zip`.

**Original issues identified:**

1. Three `rm -rf` commands in the PubMed training, PubMed merging, and health-coaching training Slurm jobs.
2. Personal `/scratch/hamzamuh` paths in YAML and Slurm jobs.
3. Health-coaching training wrote to a `health_coaching_lora_v4_3ep` directory but its job inspected `health_coaching_smoke_lora`.
4. Health-coaching merge performed a separate inline PEFT merge; the portable Slurm job now calls the repository's `merge_lora.py` with the same base, adapter, dtype, and output intent. **Weight equivalence has not yet been experimentally verified.**
5. `build_safety_imatrix_v4.slurm` originally requested statistics without supplying `--model`, which the current llama.cpp version requires.
6. The PubMed data producer, dependency lockfile, model revision, and end-to-end GPU results were not part of the submitted archive.

**What changed:**

- The numerical helper functions inside the four Python algorithms were retained. Only entrypoint preflight, path resolution, and new-workspace protection were added.
- All YAML training hyperparameters, LoRA and QLoRA settings, and pruning strategy settings were retained.
- All twelve Slurm files retain their original `#SBATCH` resource declarations. The *shell bodies* use a shared variable-based environment and never delete old outputs.
- Added `init_workspace.py`, `heart_paths.py`, `preflight_training.py`, and standard-library tests.
- A run must be directed to an explicitly initialized, newly created workspace; by default no old dataset, model, or result is changed.

**Limits of verification:** Python syntax checks, Bash syntax checks, environment/workspace unit tests, and static comparisons were done. No GPU job, fine-tuning, pruning, end-to-end model evaluation, or comparison against published training artifacts was performed in this environment. The whole HEART workflow is **not** yet claimed reproducible.
