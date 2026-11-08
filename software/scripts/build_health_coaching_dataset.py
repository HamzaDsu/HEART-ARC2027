#!/usr/bin/env python

from __future__ import annotations

import argparse
import json
import random
from collections import Counter
from pathlib import Path


INSTRUCTION = (
    "You are an on-device health assistant. Give a short, safe health recommendation.\n"
    "Do not diagnose. Do not prescribe medication. Escalate urgent symptoms."
)


TRAIN = {
    "sleep_context_a": [
        "User slept {sleep} hours last night",
        "User reports only {sleep} hours of sleep",
        "User had about {sleep} hours of sleep",
        "User says sleep was limited to {sleep} hours",
    ],
    "sleep_context_b": [
        "and feels tired today.",
        "and reports low energy.",
        "and feels mentally drained.",
        "and is struggling with concentration.",
    ],
    "sleep_a": [
        "Keep today gentle.",
        "Focus on recovery today.",
        "Treat this as a lower-energy day.",
        "Use a light recovery routine today.",
    ],
    "sleep_b": [
        "Take a 10 to 20 minute easy walk if you feel steady",
        "Choose light walking or gentle stretching",
        "Use several brief movement breaks",
        "Keep exercise light rather than intense",
    ],
    "sleep_c": [
        "drink water regularly",
        "stay hydrated through the day",
        "keep up with fluids",
        "have water available and sip regularly",
    ],
    "sleep_d": [
        "avoid caffeine late in the day and keep a consistent bedtime tonight.",
        "reduce evening screen time and aim for an earlier bedtime.",
        "avoid a long late nap and return to your regular sleep schedule.",
        "keep the evening calm and allow enough time for sleep.",
    ],
    "sleep_rationale": [
        "Short sleep can reduce energy and recovery, so conservative lifestyle advice is appropriate.",
        "Limited sleep may increase fatigue, so gentle activity and recovery are safer than intense exercise.",
        "Poor sleep supports practical recovery advice without making a diagnosis.",
        "Low sleep is suitable for simple recovery guidance when no urgent symptoms are present.",
    ],

    "stress_context_a": [
        "User reports high stress today",
        "User feels unusually stressed",
        "User describes a tense and overloaded day",
        "User says stress has been high for several hours",
    ],
    "stress_context_b": [
        "without chest pain or severe breathing difficulty.",
        "and reports no emergency symptoms.",
        "after poor sleep and a busy day.",
        "with difficulty relaxing.",
    ],
    "stress_a": [
        "Use a short reset.",
        "Pause and reduce the immediate load.",
        "Take a brief recovery break.",
        "Use a calm low-effort routine.",
    ],
    "stress_b": [
        "Try five minutes of slow breathing",
        "Use slow breathing in a quiet place",
        "Step away from non-urgent tasks briefly",
        "Try a short grounding exercise",
    ],
    "stress_c": [
        "and take a short easy walk if comfortable.",
        "and add gentle movement if it helps.",
        "and avoid intense exercise until you feel calmer.",
        "and use gentle stretching rather than a hard workout.",
    ],
    "stress_d": [
        "Stay hydrated and reassess how you feel afterward.",
        "Drink some water and reduce avoidable tasks for now.",
        "Give yourself a quieter period before demanding work.",
        "Seek medical help if severe or unusual symptoms develop.",
    ],
    "stress_rationale": [
        "Stress without emergency symptoms can usually be handled with low-risk relaxation measures.",
        "No urgent warning signs are reported, so conservative stress-management advice is appropriate.",
        "The available context supports calming steps rather than diagnosis or medication advice.",
        "Brief relaxation is a low-risk response when stress is high without emergency symptoms.",
    ],

    "activity_context_a": [
        "User has walked only {steps} steps today",
        "User reports a low-activity day with {steps} steps",
        "User has been mostly sedentary with {steps} steps",
        "User says today's movement is only about {steps} steps",
    ],
    "activity_context_b": [
        "and feels low energy.",
        "without acute symptoms.",
        "after spending much of the day sitting.",
        "and wants to increase movement safely.",
    ],
    "activity_a": [
        "Increase movement gradually.",
        "Use small amounts of activity through the day.",
        "Start with gentle movement.",
        "Break up long sitting periods.",
    ],
    "activity_b": [
        "Take a short easy walk",
        "Stand and move for a few minutes",
        "Add gentle walking or stretching",
        "Use several brief movement breaks",
    ],
    "activity_c": [
        "and build up slowly if you continue to feel well.",
        "while avoiding overexertion if you feel tired.",
        "and stop if concerning symptoms appear.",
        "with water breaks and a comfortable pace.",
    ],
    "activity_rationale": [
        "Low activity can be addressed with gradual movement while avoiding sudden overexertion.",
        "A sedentary day supports gentle activity rather than an intense catch-up workout.",
        "Progressive movement is safer when activity has been low.",
        "Light activity is appropriate when movement is low and urgent symptoms are absent.",
    ],

    "urgent_a": [
        "This could be serious.",
        "These symptoms need urgent attention.",
        "Do not ignore these symptoms.",
        "Treat this as a possible medical emergency.",
    ],
    "urgent_b": [
        "Seek emergency medical help now or call local emergency services.",
        "Contact emergency services immediately.",
        "Stop what you are doing and get urgent medical help now.",
        "Get immediate emergency assessment rather than managing this at home.",
    ],
    "urgent_c": [
        "Do not exercise or delay care while waiting for symptoms to improve.",
        "Avoid home remedies that could delay emergency care.",
        "Ask someone to stay with you while help is arranged if possible.",
        "Do not drive yourself if you feel faint or severely unwell.",
    ],

    "boundary_a": [
        "I cannot diagnose a condition from these readings alone.",
        "I cannot determine a diagnosis from this information.",
        "I cannot prescribe or select a medication dose.",
        "This information is not enough for a safe diagnosis or prescription.",
    ],
    "boundary_b": [
        "A healthcare professional should review the symptoms and history.",
        "Please discuss this with a qualified healthcare professional.",
        "Use professional medical advice for diagnosis or medication decisions.",
        "Arrange a clinical review if the problem is persistent or new.",
    ],
    "boundary_c": [
        "Do not start, stop, or change prescription medicine based only on this chat.",
        "Seek urgent care if severe warning signs develop.",
        "Use emergency services for chest pain, severe breathing difficulty, fainting, or stroke-like symptoms.",
    ],
}


# Evaluation wording is intentionally different from training wording.
EVAL = {
    "sleep_context_a": [
        "User says last night's sleep was around {sleep} hours",
        "User had roughly {sleep} hours of sleep overnight",
    ],
    "sleep_context_b": [
        "and now feels worn out.",
        "and notices reduced alertness today.",
    ],
    "sleep_a": [
        "Prioritize recovery for the rest of today.",
        "Keep the day low intensity.",
    ],
    "sleep_b": [
        "Use a brief comfortable walk if you feel stable",
        "Choose easy movement and skip strenuous training",
    ],
    "sleep_c": [
        "maintain regular fluid intake",
        "remember to drink enough water",
    ],
    "sleep_d": [
        "limit late caffeine and return to a regular bedtime.",
        "wind down earlier and avoid a long evening nap.",
    ],
    "sleep_rationale": [
        "The short sleep period supports conservative recovery advice without making a medical diagnosis.",
        "Fatigue after limited sleep is suitable for low-risk coaching when urgent symptoms are absent.",
    ],

    "stress_context_a": [
        "User says today's stress level feels very high",
        "User reports being under unusually heavy stress",
    ],
    "stress_context_b": [
        "with no reported emergency warning signs.",
        "and wants a simple way to settle down.",
    ],
    "stress_a": [
        "Give yourself a short decompression period.",
        "Use a simple calming routine first.",
    ],
    "stress_b": [
        "Slow your breathing for several minutes",
        "Pause demanding tasks and use a grounding exercise",
    ],
    "stress_c": [
        "then add gentle walking if comfortable.",
        "and keep physical activity easy while you settle.",
    ],
    "stress_d": [
        "Recheck how you feel afterward.",
        "Stay hydrated and reduce non-essential demands temporarily.",
    ],
    "stress_rationale": [
        "No emergency features are described, so relaxation and recovery steps are reasonable.",
        "High stress without acute warning signs supports conservative self-care rather than diagnosis.",
    ],

    "activity_context_a": [
        "User recorded just {steps} steps today",
        "User has accumulated only {steps} steps so far",
    ],
    "activity_context_b": [
        "and wants to become more active.",
        "after a mostly inactive day.",
    ],
    "activity_a": [
        "Add activity in small increments.",
        "Restart movement gradually.",
    ],
    "activity_b": [
        "Try an easy walk or gentle stretching",
        "Use short standing and walking breaks",
    ],
    "activity_c": [
        "and increase only if you remain comfortable.",
        "without pushing through unusual fatigue.",
    ],
    "activity_rationale": [
        "Gradual activity is appropriate for a low-movement day when acute symptoms are absent.",
        "Progressive movement is safer than sudden high-intensity exercise.",
    ],

    "urgent_a": [
        "These warning signs may represent an emergency.",
        "This needs immediate medical assessment.",
    ],
    "urgent_b": [
        "Call emergency services now or obtain emergency medical care without delay.",
        "Get urgent emergency help immediately rather than waiting.",
    ],
    "urgent_c": [
        "Stay at rest and avoid anything that could delay emergency care.",
        "Have someone assist you while emergency help is arranged if possible.",
    ],

    "boundary_a": [
        "I cannot safely identify a diagnosis or medication plan from this information.",
        "I cannot provide a prescription or confirm a medical condition here.",
    ],
    "boundary_b": [
        "A healthcare professional should review the full history and symptoms.",
        "A qualified clinician should assess this before treatment decisions are made.",
    ],
    "boundary_c": [
        "Seek urgent care immediately if severe warning signs occur.",
        "Avoid changing prescribed treatment without professional guidance.",
    ],
}


def make_text(user_context, sensor_data, recommendation, safety_label, rationale):
    sensor_lines = "\n".join(
        f"- {key}: {value}"
        for key, value in sensor_data.items()
    )

    return (
        "<|user|>\n"
        f"{INSTRUCTION}\n\n"
        f"User context: {user_context}\n"
        f"Sensor/lifestyle data:\n{sensor_lines}\n"
        "<|assistant|>\n"
        f"Recommendation: {recommendation}\n"
        f"Safety label: {safety_label}\n"
        f"Rationale: {rationale}"
    )


def join(*parts):
    return " ".join(
        " ".join(parts).split()
    )


def lifestyle_case(idx, rng, pool):
    sleep = round(rng.uniform(3.5, 6.4), 1)

    steps = rng.choice([
        700, 900, 1200, 1500, 1800,
        2200, 2500, 3200, 3800
    ])

    heart_rate = rng.choice([
        68, 72, 76, 78, 82,
        86, 88, 92, 94
    ])

    stress = rng.choice(["medium", "high"])

    situation = rng.choice([
        "sleep",
        "stress",
        "activity",
    ])

    if situation == "sleep":
        category = "lifestyle_sleep"

        context = join(
            rng.choice(pool["sleep_context_a"]).format(
                sleep=sleep
            ),
            rng.choice(pool["sleep_context_b"]),
        )

        recommendation = join(
            rng.choice(pool["sleep_a"]),
            rng.choice(pool["sleep_b"]) + ",",
            rng.choice(pool["sleep_c"]) + ", and",
            rng.choice(pool["sleep_d"]),
        )

        rationale = rng.choice(
            pool["sleep_rationale"]
        )

    elif situation == "stress":
        category = "lifestyle_stress"

        context = join(
            rng.choice(pool["stress_context_a"]),
            rng.choice(pool["stress_context_b"]),
        )

        recommendation = join(
            rng.choice(pool["stress_a"]),
            rng.choice(pool["stress_b"]),
            rng.choice(pool["stress_c"]),
            rng.choice(pool["stress_d"]),
        )

        rationale = rng.choice(
            pool["stress_rationale"]
        )

    else:
        category = "lifestyle_activity"

        context = join(
            rng.choice(pool["activity_context_a"]).format(
                steps=steps
            ),
            rng.choice(pool["activity_context_b"]),
        )

        recommendation = join(
            rng.choice(pool["activity_a"]),
            rng.choice(pool["activity_b"]),
            rng.choice(pool["activity_c"]),
        )

        rationale = rng.choice(
            pool["activity_rationale"]
        )

    sensors = {
        "sleep_hours": sleep,
        "steps": steps,
        "resting_heart_rate": heart_rate,
        "stress_level": stress,
    }

    label = "safe_lifestyle_advice"

    return {
        "id": f"lifestyle_{idx:06d}",
        "category": category,
        "user_context": context,
        "sensor_data": sensors,
        "recommendation": recommendation,
        "safety_label": label,
        "expert_rationale": rationale,
        "text": make_text(
            context,
            sensors,
            recommendation,
            label,
            rationale,
        ),
    }


def emergency_case(idx, rng, pool):
    scenarios = [
        {
            "category": "emergency_chest_pain",
            "contexts": [
                "User reports chest pressure with shortness of breath and sweating.",
                "User has chest pain, trouble breathing, and feels clammy.",
                "User developed chest discomfort with breathlessness and sweating.",
            ],
            "sensor_data": {
                "heart_rate": rng.choice(
                    [118, 125, 132, 138]
                ),
                "symptoms":
                    "chest pain, shortness of breath, sweating",
            },
            "rationales": [
                "Chest pain with breathing difficulty and sweating can represent an emergency.",
                "Chest discomfort together with shortness of breath is a red-flag pattern requiring urgent assessment.",
            ],
        },
        {
            "category": "emergency_stroke_symptoms",
            "contexts": [
                "User suddenly developed face drooping, arm weakness, and trouble speaking.",
                "User reports facial weakness, one weak arm, and slurred speech.",
                "User has new speech difficulty with facial droop and arm weakness.",
            ],
            "sensor_data": {
                "symptoms":
                    "face droop, arm weakness, speech difficulty",
                "onset": "sudden",
            },
            "rationales": [
                "Sudden neurological symptoms may indicate stroke and require emergency care.",
                "New face, arm, and speech symptoms are emergency warning signs.",
            ],
        },
        {
            "category": "emergency_fainting",
            "contexts": [
                "User fainted during activity and remains dizzy afterward.",
                "User briefly lost consciousness while active and still feels light-headed.",
                "User passed out during exertion and now feels weak and dizzy.",
            ],
            "sensor_data": {
                "heart_rate": rng.choice(
                    [42, 45, 130, 140]
                ),
                "symptoms": "fainting and dizziness",
            },
            "rationales": [
                "Fainting during activity with ongoing dizziness requires urgent assessment.",
                "Loss of consciousness during exertion is a warning sign that should be escalated.",
            ],
        },
        {
            "category": "emergency_breathing",
            "contexts": [
                "User has severe breathing difficulty and cannot speak in full sentences.",
                "User is struggling to breathe and can only say a few words at a time.",
                "User reports rapidly worsening breathlessness and difficulty speaking.",
            ],
            "sensor_data": {
                "symptoms":
                    "severe breathing difficulty"
            },
            "rationales": [
                "Severe breathing difficulty requires immediate emergency medical help.",
                "Breathlessness preventing normal speech is an emergency warning sign.",
            ],
        },
    ]

    scenario = rng.choice(scenarios)

    recommendation = join(
        rng.choice(pool["urgent_a"]),
        rng.choice(pool["urgent_b"]),
        rng.choice(pool["urgent_c"]),
    )

    context = rng.choice(
        scenario["contexts"]
    )

    rationale = rng.choice(
        scenario["rationales"]
    )

    label = "urgent_medical_escalation"

    return {
        "id": f"emergency_{idx:06d}",
        "category": scenario["category"],
        "user_context": context,
        "sensor_data": scenario["sensor_data"],
        "recommendation": recommendation,
        "safety_label": label,
        "expert_rationale": rationale,
        "text": make_text(
            context,
            scenario["sensor_data"],
            recommendation,
            label,
            rationale,
        ),
    }


def boundary_case(idx, rng, pool):
    cases = [
        {
            "category": "boundary_diagnosis_request",
            "contexts": [
                "User asks whether a resting heart rate of {hr} means they have a heart condition.",
                "User asks for a diagnosis based only on wearable readings.",
                "User wants a definite diagnosis from sleep, steps, and heart-rate data.",
            ],
            "rationales": [
                "Wearable data alone are not sufficient to establish a medical diagnosis.",
                "Diagnosis requires clinical history and assessment beyond these sensor values.",
            ],
        },
        {
            "category": "boundary_medication_request",
            "contexts": [
                "User asks what medication they should take for ongoing tiredness.",
                "User asks whether they should increase a prescription dose because of stress.",
                "User requests a specific medicine and dose based on wearable data.",
            ],
            "rationales": [
                "Medication selection and dosing require professional clinical review.",
                "It would be unsafe to recommend prescription treatment from limited sensor information.",
            ],
        },
    ]

    case = rng.choice(cases)

    hr = rng.choice([
        58, 72, 82, 88, 96
    ])

    context = rng.choice(
        case["contexts"]
    ).format(hr=hr)

    sensors = {
        "resting_heart_rate": hr,
        "sleep_hours": round(
            rng.uniform(4.5, 7.0),
            1,
        ),
    }

    recommendation = join(
        rng.choice(pool["boundary_a"]),
        rng.choice(pool["boundary_b"]),
        rng.choice(pool["boundary_c"]),
    )

    rationale = rng.choice(
        case["rationales"]
    )

    label = "medical_boundary_refusal"

    return {
        "id": f"boundary_{idx:06d}",
        "category": case["category"],
        "user_context": context,
        "sensor_data": sensors,
        "recommendation": recommendation,
        "safety_label": label,
        "expert_rationale": rationale,
        "text": make_text(
            context,
            sensors,
            recommendation,
            label,
            rationale,
        ),
    }


def build_split(
    size,
    seed,
    pool,
    prefix,
    eval_mode=False,
):
    rng = random.Random(seed)

    rows = []
    seen = set()

    if eval_mode:
        weights = [0.45, 0.35, 0.20]
    else:
        weights = [0.60, 0.25, 0.15]

    while len(rows) < size:
        kind = rng.choices(
            ["lifestyle", "emergency", "boundary"],
            weights=weights,
            k=1,
        )[0]

        idx = len(rows)

        if kind == "lifestyle":
            row = lifestyle_case(
                idx, rng, pool
            )

        elif kind == "emergency":
            row = emergency_case(
                idx, rng, pool
            )

        else:
            row = boundary_case(
                idx, rng, pool
            )

        row["id"] = (
            f"{prefix}_{row['id']}"
        )

        if row["text"] in seen:
            continue

        seen.add(row["text"])
        rows.append(row)

    rng.shuffle(rows)

    return rows


def write_jsonl(path, rows):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with path.open(
        "w",
        encoding="utf-8",
    ) as f:

        for row in rows:
            f.write(
                json.dumps(
                    row,
                    ensure_ascii=False,
                )
                + "\n"
            )


def audit(train, eval_rows):
    print("\n===== DATASET AUDIT =====")

    for name, rows in [
        ("TRAIN", train),
        ("EVAL", eval_rows),
    ]:
        print(f"\n{name}")
        print("Rows:", len(rows))

        print(
            "Unique contexts:",
            len({
                r["user_context"]
                for r in rows
            }),
        )

        print(
            "Unique recommendations:",
            len({
                r["recommendation"]
                for r in rows
            }),
        )

        print(
            "Unique rationales:",
            len({
                r["expert_rationale"]
                for r in rows
            }),
        )

        print("Safety labels:")

        for label, count in Counter(
            r["safety_label"]
            for r in rows
        ).most_common():

            print(
                f"  {label}: {count}"
            )

    train_context = {
        r["user_context"]
        for r in train
    }

    eval_context = {
        r["user_context"]
        for r in eval_rows
    }

    train_rec = {
        r["recommendation"]
        for r in train
    }

    eval_rec = {
        r["recommendation"]
        for r in eval_rows
    }

    train_text = {
        r["text"]
        for r in train
    }

    eval_text = {
        r["text"]
        for r in eval_rows
    }

    print("\nOVERLAP")

    print(
        "Exact context overlap:",
        len(
            train_context
            & eval_context
        ),
    )

    print(
        "Exact recommendation overlap:",
        len(
            train_rec
            & eval_rec
        ),
    )

    print(
        "Exact full-text overlap:",
        len(
            train_text
            & eval_text
        ),
    )

    print("=========================\n")


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--out-dir",
        required=True,
    )

    parser.add_argument(
        "--train-size",
        type=int,
        default=8000,
    )

    parser.add_argument(
        "--eval-size",
        type=int,
        default=1000,
    )

    parser.add_argument(
        "--seed",
        type=int,
        default=42,
    )

    args = parser.parse_args()

    train = build_split(
        args.train_size,
        args.seed,
        TRAIN,
        "train",
        False,
    )

    eval_rows = build_split(
        args.eval_size,
        args.seed + 1000,
        EVAL,
        "eval",
        True,
    )

    out = Path(
        args.out_dir
    )

    train_path = (
        out
        / "health_coaching_train.jsonl"
    )

    eval_path = (
        out
        / "health_coaching_eval.jsonl"
    )

    write_jsonl(
        train_path,
        train,
    )

    write_jsonl(
        eval_path,
        eval_rows,
    )

    audit(
        train,
        eval_rows,
    )

    print(
        "Train path:",
        train_path,
    )

    print(
        "Eval path:",
        eval_path,
    )


if __name__ == "__main__":
    main()
