def dominates(a, b):
    """True when a is no worse than b on all objectives
    and strictly better on at least one.
    """
    ar = a["resource_estimates"]
    br = b["resource_estimates"]

    better_or_equal = (
        a["score"] >= b["score"]
        and ar["luts"] <= br["luts"]
        and ar["bram36_equivalent"] <= br["bram36_equivalent"]
    )

    strictly_better = (
        a["score"] > b["score"]
        or ar["luts"] < br["luts"]
        or ar["bram36_equivalent"] < br["bram36_equivalent"]
    )

    return better_or_equal and strictly_better


def pareto_front(candidates):
    front = []

    for i, candidate in enumerate(candidates):
        dominated = any(
            dominates(other, candidate)
            for j, other in enumerate(candidates)
            if i != j
        )

        if not dominated:
            front.append(candidate)

    return sorted(
        front,
        key=lambda item: (
            -item["score"],
            item["resource_estimates"]["luts"],
            item["resource_estimates"]["bram36_equivalent"],
        ),
    )
