# HEART-ARC2027 E2: controlled Q3_K_S quantization reproducibility

## Status (2026-11-08)

The reference FP16 unpruned model and the pinned `llama.cpp` revision were used for all three E2 variants. The output models were compared with the original artifacts using `software/scripts/compare_gguf.py`:

| Mode | Recorded original SHA-256 | Reproduction finding |
|---|---|---|
| No imatrix | `20de757d13a27313278535f03b0c101a66ab0687e15ca040507666f871e4ab02` | Complete file SHA-256 identical, 201/201 tensors identical |
| Uniform-96 | `3daeffc8751206c2a84e5363c9197a8138a0eff1865243ec571c12ee65bb695c` | 201/201 tensors identical; only `quantize.imatrix.dataset` and `quantize.imatrix.file` metadata differ |
| Safety-weighted | `9d22702da628362269ff0e2847cfc55d5e65b6e217134f97e7026c7e1610fbb0` | 201/201 tensors identical; only `quantize.imatrix.file` metadata differs; this run **reused the original safety imatrix** |

The independently regenerated uniform-96 importance matrix had the same numerical content and remaining bytes as the original; its embedded calibration path and alignment padding differed. **Independent safety imatrix generation is still required** to establish full end-to-end regeneration using the checked-in calibration file.

The full-file hashes of the reference artifacts are stored in `software/results/e2_reference_hashes.json`. An SHA mismatch must never be silently ignored or replaced by a newly computed "expected" hash.

## Safe design

`reproduce_e2.py` previews commands by default. It refuses to overwrite any generated GGUF. Use a separate scratch directory for each fresh run, preferably on a Slurm CPU node. It checks the calibration SHA-256 and pinned llama.cpp revision, then generates matrices and Q3_K_S models.

When GGUF metadata contains absolute source paths, the **whole-file** SHA can differ even when the numerical data is the same. Version 2 therefore permits an explicit, read-only comparison with an unmodified original artifact supplied as `--reference ARTIFACT_NAME=/absolute/path`. Acceptance requires:

1. The supplied reference's full SHA equals its recorded original digest.
2. GGUF version and every non-provenance metadata key/value match; arrays in importance matrices are compared by SHA-256.
3. All tensor names, shapes, types and payload bytes match.
4. Any changed metadata keys are a strict allowlist of calibration/imatrix *path provenance* fields only.

The script labels results `sha256_exact` or `payload_exact_metadata_path_diff`. These are not interchangeable. The model has not been clinically validated by these byte-level reproduction tests.

## Verify current E2 reproductions (no new job)

From the repository root, execute the following, replacing paths as needed (this command reads existing files only):

```bash
python software/scripts/reproduce_e2.py \
  --llama-dir /scratch/hamzamuh/tools/llama.cpp \
  --fp16 /scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/health-slm-v4-f16.gguf \
  --output-dir /scratch/hamzamuh/health-slm-experiments/e2_reproduction_check \
  --mode all --phase verify \
  --reference uniform_imatrix_96.gguf=/scratch/hamzamuh/health-slm-experiments/e2_ablation/uniform_imatrix_96.gguf \
  --reference v4_q3ks_uniform96.gguf=/scratch/hamzamuh/health-slm-experiments/e2_ablation/v4_q3ks_uniform96.gguf \
  --reference v4_q3ks_safety_rebuilt.gguf=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/health-slm-v4-Q3_K_S-safety.gguf
```

The original safety matrix was copied into the current output directory, so its whole-file SHA should already match. The no-imatrix model likewise matched its original whole-file SHA.

## Independently regenerate safety-weighted matrix AND model

Use a fresh output directory, never the directory holding the original models or the earlier copied matrix. The v2 `--reference` arguments make path-only differences explicit and acceptable only after strict semantic verification.

Submit this from the repository root on Willi (shell is `tcsh`, `sbatch --wrap` command uses Bash in the batch):

```bash
sbatch --partition=students --cpus-per-task=8 --mem=16G --time=02:00:00 \
  --output=/scratch/hamzamuh/health-slm-experiments/e2_safety_independent_%j.log \
  --wrap='cd /home/hamzamuh/projects/HEART-ARC2027 && python software/scripts/reproduce_e2.py --llama-dir /scratch/hamzamuh/tools/llama.cpp --fp16 /scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/health-slm-v4-f16.gguf --output-dir /scratch/hamzamuh/health-slm-experiments/e2_safety_independent --mode safety --phase all --execute --reference safety_imatrix.gguf=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/safety_imatrix.gguf --reference v4_q3ks_safety_rebuilt.gguf=/scratch/hamzamuh/health-slm-experiments/deployment_v4_unpruned/health-slm-v4-Q3_K_S-safety.gguf'
```

After the job, inspect its `sacct` exit code and its Slurm output log. A successful verification prints `[PAYLOAD MATCH] safety_imatrix.gguf` and `[PAYLOAD MATCH] v4_q3ks_safety_rebuilt.gguf` or `[SHA256 MATCH]` if complete files happen to match.

If any verification fails, retain its generated artifacts and examine them; **never update reference hashes to make a mismatch pass**.

## Evidence limitations

The original uniform and no-imatrix CLI commands were reconstructed from the pinned tool version and checked against actual tensor payloads. The original safety calibration command was preserved in `software/slurm/build_safety_imatrix_v4.slurm`. The FP16 GGUF's byte count is checked before generation; a trusted reference SHA for that original FP16 file has not yet been added to the checked-in reference JSON. The E1/E2 data contain train/eval prompt overlap; therefore these steps verify generation reproducibility, not clinical safety or an independently held-out assessment.


## Verifier v3 correction (2026-11-08)

Job 4023 successfully generated `e2_safety_independent/safety_imatrix.gguf`, but v2
failed before quantizing because it searched GGUF **metadata** for keys ending in
`.in_sum2` / `.counts`. llama.cpp stores those numeric importance scores as GGUF
**tensors**, not metadata. v3 checks that these numeric tensors exist, compares
all their layouts and bytes with the known original, and verifies the changed
`imatrix.datasets` metadata contains the same expected calibration file basename.

**No rerun of the importance-matrix computation is necessary.** Re-run the same
`--mode safety --phase all --execute --reference ...` invocation against the
existing independent output directory. The script will preserve the generated
matrix, verify its payload and, only if successful, continue to quantization.
The independent safety run is not confirmed until both steps pass.
