#!/usr/bin/env python3
"""Generate a model-bound C inference launcher for a GGUF file."""

import argparse
import json
import re
from pathlib import Path

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("model", type=Path)
    args = parser.parse_args()

    model = args.model.expanduser().resolve()

    if not model.is_file() or model.suffix.lower() != ".gguf":
        parser.error("Expected an existing .gguf model")

    output_dir = Path(__file__).resolve().parent / "generated"
    output_dir.mkdir(parents=True, exist_ok=True)

    name = re.sub(r"[^A-Za-z0-9_-]", "_", model.stem)
    output = output_dir / f"{name}.c"

    model_literal = json.dumps(str(model))
    prompt_literal = json.dumps(
        "Give three general tips for maintaining a healthy lifestyle."
    )

    source = f"""\
/* Automatically generated GGUF inference launcher.
 * Inference is provided by the existing llama.cpp C API.
 */

#define main slm_runtime_main
#include "../healthslm_c_api.c"
#undef main

int main(int argc, char **argv) {{
    if (argc > 2) {{
        fprintf(stderr, "Usage: %s [PROMPT]\\n", argv[0]);
        return 2;
    }}

    char *arguments[] = {{
        argv[0],
        (char *) {model_literal},
        (char *) (argc == 2 ? argv[1] : {prompt_literal}),
        NULL
    }};

    return slm_runtime_main(3, arguments);
}}
"""

    output.write_text(source, encoding="utf-8")

    print("Generated C source:", output)
    print("Model:", model)
    print("Backend: llama.cpp C API")

if __name__ == "__main__":
    main()
