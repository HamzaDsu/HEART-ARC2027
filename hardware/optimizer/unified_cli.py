"""Common Nexys screening CLI: model adapters -> shared objective ranker."""
import argparse
import csv
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODELS = ('healthslm', 'resnet18', 'vgg11', 'swint', 'levit384')
OBJECTIVES = ('balanced', 'performance', 'resources', 'power')


def read(path):
    return json.loads(Path(path).read_text())


def references(board):
    refs = {}
    for label in ('baseline', 'optimized'):
        path = ROOT / f'results/optimizer/measurements/{board}_{label}.json'
        if not path.is_file():
            raise FileNotFoundError(f'Missing measured board reference: {path}')
        refs[label] = read(path)
    if len({r['fpga_part'] for r in refs.values()}) != 1:
        raise ValueError('Board reference FPGA parts do not match')
    return refs


def model_scores(model, refs, gguf):
    if model == 'healthslm':
        from optimizer.optimization.model_aware import model_fit
        if gguf:
            from optimizer.model_analyzer.workload_profile import build_workload_profile
            path = Path(gguf).expanduser()
            if not path.is_file():
                raise FileNotFoundError(path)
            profile = build_workload_profile(path)
            source = f'GGUF metadata: {path}'
        else:
            path = ROOT / 'results/optimizer/final_health_slm_balanced.json'
            profile = read(path)['workload_profile']
            source = 'saved HealthSLM workload profile (no new inference)'
        scores = {name: float(model_fit(ref['configuration'], profile)['factor'])
                  for name, ref in refs.items()}
        return scores, source, 'model_fit_heuristic_not_throughput'

    from optimizer.optimization.vision_workload_recommender import evaluate
    profile = read(ROOT / f'benchmarks/quantized_networks/profiles/{model}.json')
    execution = read(ROOT / f'results/optimizer/integer_execution_profiles/{model}.json')
    scores = {name: float(evaluate(profile, execution, ref, name)['score'])
              for name, ref in refs.items()}
    return scores, 'saved quantized vision workload profiles', 'compute_weighted_tile_fit_not_throughput'


def select(candidates, objective):
    def criterion(c):
        if objective == 'performance':
            return (-c['score'], c['resource_cost'])
        if objective == 'resources':
            return (c['resource_cost'], -c['score'])
        if objective == 'power':
            return (c['switching_proxy'], -c['score'])
        return (-c['score'] + .25 * c['resource_cost'], c['resource_cost'])
    return sorted(candidates, key=criterion)


def run(board, gguf, output):
    refs = references(board)
    base = refs['baseline']['measured_resources']
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    unique = {}

    for model in MODELS:
        scores, source, score_type = model_scores(model, refs, gguf)
        candidates = []

        for name, ref in refs.items():
            r = ref['measured_resources']
            cfg = ref['configuration']
            cost = sum(w * float(r[k]) / max(float(base[k]), 1)
                       for k, w in [('luts', .35), ('flip_flops', .15),
                                    ('bram36_equivalent', .40), ('dsp', .10)])
            switching = ((.6 * r['luts'] / base['luts']) +
                         (.4 * r['flip_flops'] / base['flip_flops'])) * (
                         cfg['target_clock_mhz'] / refs['baseline']['configuration']['target_clock_mhz'])

            candidates.append({
                'reference': name,
                'configuration': cfg,
                'measured_resources': r,
                'score': scores[name],
                'resource_cost': round(cost, 6),
                'switching_proxy': round(switching, 6)
            })

        for objective in OBJECTIVES:
            ordered = select(candidates, objective)
            chosen = ordered[0]

            report = {
                'model': model,
                'board': board,
                'fpga_part': refs['baseline']['fpga_part'],
                'objective': objective,
                'status': 'reference_screening_only',
                'model_input': source,
                'score_type': score_type,
                'selected_reference': chosen['reference'],
                'selected_configuration': chosen['configuration'],
                'ranked_candidates': ordered,
                'limitations': [
                    'Only two existing implemented board references considered.',
                    'Vivado resources are measured for hardware, not full model inference.',
                    'Scores are proxies, not measured latency or throughput.',
                    'Switching proxy is not measured energy or power.',
                    'No full-model Rocket RTL or physical FPGA inference executed.'
                ]
            }

            (output / f'{model}_{objective}.json').write_text(
                json.dumps(report, indent=2) + '\n'
            )

            rows.append({
                'model': model,
                'objective': objective,
                'selected': chosen['reference'],
                'score': chosen['score'],
                'score_type': score_type,
                'luts': chosen['measured_resources']['luts'],
                'bram36': chosen['measured_resources']['bram36_equivalent']
            })

            k = json.dumps(chosen['configuration'], sort_keys=True)
            unique.setdefault(k, {
                'configuration': chosen['configuration'],
                'uses': []
            })['uses'].append(f'{model}:{objective}')

            print(
                f"{model:10} {objective:11} -> "
                f"{chosen['reference']:9} | proxy={chosen['score']:.5f}"
            )

    with (output / 'summary.csv').open('w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=rows[0].keys(), lineterminator='\n')
        w.writeheader()
        w.writerows(rows)

    (output / 'unique_hardware_configs.json').write_text(
        json.dumps(list(unique.values()), indent=2) + '\n'
    )

    print(
        f'Wrote {len(rows)} objective reports; '
        f'{len(unique)} distinct hardware configurations'
    )


# SaCriS-V opt-in dispatch.
# Default Rocket behavior remains unchanged.
def _dispatch_processor():
    import sys

    args = sys.argv[1:]

    matches = [
        i for i, arg in enumerate(args)
        if arg == "--processor"
        or arg.startswith("--processor=")
    ]

    if not matches:
        return False

    if len(matches) != 1:
        raise SystemExit("Specify --processor only once")

    index = matches[0]

    if args[index] == "--processor":
        if index + 1 >= len(args):
            raise SystemExit(
                "--processor requires rocket or sacris-v"
            )

        processor = args[index + 1]
        remaining = args[:index] + args[index + 2:]
    else:
        processor = args[index].split("=", 1)[1]
        remaining = args[:index] + args[index + 1:]

    if processor == "rocket":
        sys.argv = [sys.argv[0], *remaining]
        return False

    if processor != "sacris-v":
        raise SystemExit(
            "--processor must be rocket or sacris-v"
        )

    from optimizer.optimization.sacris_v_unified import (
        main as sacris_main,
    )

    sacris_main(remaining)
    return True


def main():
    if _dispatch_processor():
        return
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--board', default='nexysvideo')
    p.add_argument('--gguf', default=os.getenv('HEALTHSLM_GGUF'))
    p.add_argument(
        '--output-dir',
        default=str(ROOT / 'results/optimizer/unified_nexys')
    )
    p.add_argument(
        "--mode",
        choices=("references", "explore"),
        default="references"
    )
    p.add_argument(
        "--model",
        choices=("all", "healthslm", "resnet18",
                 "vgg11", "swint", "levit384"),
        default="all"
    )
    p.add_argument("--external-ram-mib", type=int)
    p.add_argument("--runtime-context-length", type=int)
    p.add_argument("--top", type=int, default=5)

    a = p.parse_args()

    if a.mode == "explore":
        from optimizer.optimization.unified_search import run_search
        destination = Path(a.output_dir)
        if destination == ROOT / "results/optimizer/unified_nexys":
            destination = ROOT / "results/optimizer/unified_search_nexys"
        run_search(
            a.board, a.gguf, destination,
            model=a.model,
            ram_mib=a.external_ram_mib,
            context=a.runtime_context_length,
            top=a.top
        )
    else:
        run(a.board, a.gguf, Path(a.output_dir))


if __name__ == '__main__':
    main()
