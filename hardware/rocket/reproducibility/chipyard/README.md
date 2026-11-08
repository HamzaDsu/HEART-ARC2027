# Chipyard Reproducibility Notes

## Preserved evidence

- `chipyard_commit.txt`: Base Chipyard commit.
- `chipyard_submodules_recursive.txt`: Recursive submodule revisions.
- `chipyard_parent_changes.patch`: Tracked parent repository differences.
- `rocket_configs.patch`: Custom HealthSLM Rocket configurations.
- `submodule_patches/fpga/fpga-shells.patch`: FPGA-shell modification.
- `chipyard_status.txt`: Working-tree status when captured.

## Important distinction

The parent patch contains Conda dependency changes, custom
Rocket configurations, and submodule gitlink differences.

It is a historical snapshot, not a validated one-command
rebuild patch.

## Submodule checkout anomalies

The following submodule working trees showed exclusively
tracked-file deletions during inspection:

- tools/axe: 51 deletions
- tools/torture: 41 deletions
- QEMU roms/qboot: 39 deletions
- QEMU roms/qemu-palcode: 38 deletions

Their large deletion patches were retained locally but
intentionally excluded from the staged research package.

They should not be applied to a fresh checkout.

Investigate normal submodule initialization and revision
checkout before attempting any recovery.

## Rebuild status

The existing local Chipyard and Verilator workflow has
passed the recorded research benchmarks.

A clean WSL/Ubuntu rebuild has not yet been validated.

Vivado resource measurements are implementation results,
not physical FPGA execution measurements.

Do not assume the research manifest captures every
untracked file or binary dependency in Chipyard.
