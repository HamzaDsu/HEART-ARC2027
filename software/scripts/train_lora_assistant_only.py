#!/usr/bin/env python
"""LoRA/QLoRA fine-tuning entrypoint for health SLM thesis experiments.

This version uses standard Hugging Face Trainer + PEFT instead of TRL SFTTrainer.
Reason:
- TRL APIs change frequently.
- Hugging Face Trainer is more stable for terminal/server thesis experiments.
- Works with JSONL files that contain a `text` field.
"""

from __future__ import annotations

import argparse
import inspect
from pathlib import Path

from heart_paths import load_resolved_config, require_file, require_model, reserve_new_output

import torch
from datasets import load_dataset
from peft import LoraConfig, get_peft_model, prepare_model_for_kbit_training
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    BitsAndBytesConfig,
    DataCollatorForLanguageModeling,
    Trainer,
    TrainingArguments,
)

DTYPES = {
    "float16": torch.float16,
    "bfloat16": torch.bfloat16,
    "float32": torch.float32,
}


def make_quantization_config(qcfg: dict) -> BitsAndBytesConfig | None:
    if not qcfg.get("load_in_4bit", False):
        return None

    compute_dtype = DTYPES.get(qcfg.get("bnb_4bit_compute_dtype", "float16"), torch.float16)

    return BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type=qcfg.get("bnb_4bit_quant_type", "nf4"),
        bnb_4bit_compute_dtype=compute_dtype,
        bnb_4bit_use_double_quant=qcfg.get("bnb_4bit_use_double_quant", True),
    )


def filter_supported_kwargs(cls, kwargs: dict) -> dict:
    """Keep only kwargs supported by the installed transformers version."""
    try:
        params = inspect.signature(cls.__init__).parameters
    except Exception:
        return kwargs
    return {k: v for k, v in kwargs.items() if k in params}


def build_training_args(cfg: dict, quantized: bool) -> TrainingArguments:
    t = cfg["training"]

    kwargs = {
        "output_dir": cfg["output_dir"],
        "num_train_epochs": t["num_train_epochs"],
        "per_device_train_batch_size": t["per_device_train_batch_size"],
        "per_device_eval_batch_size": t["per_device_eval_batch_size"],
        "gradient_accumulation_steps": t["gradient_accumulation_steps"],
        "learning_rate": t["learning_rate"],
        "warmup_ratio": t.get("warmup_ratio", 0.03),
        "logging_steps": t["logging_steps"],
        "eval_steps": t["eval_steps"],
        "save_steps": t["save_steps"],
        "save_total_limit": t.get("save_total_limit", 2),
        "save_strategy": "steps",
        "fp16": t.get("fp16", True),
        "bf16": t.get("bf16", False),
        "gradient_checkpointing": t.get("gradient_checkpointing", True),
        "optim": t.get("optim", "paged_adamw_8bit" if quantized else "adamw_torch"),
        "report_to": t.get("report_to", "none"),
        "run_name": cfg.get("run_name"),
        "remove_unused_columns": False,
    }

    # Transformers versions differ: some use eval_strategy, older ones use evaluation_strategy.
    params = inspect.signature(TrainingArguments.__init__).parameters
    if "eval_strategy" in params:
        kwargs["eval_strategy"] = "steps"
    elif "evaluation_strategy" in params:
        kwargs["evaluation_strategy"] = "steps"

    return TrainingArguments(**filter_supported_kwargs(TrainingArguments, kwargs))



def find_subsequence(sequence, subsequence):
    """Return the first position of subsequence inside sequence."""
    if not subsequence:
        return -1

    n = len(subsequence)

    for i in range(len(sequence) - n + 1):
        if sequence[i:i + n] == subsequence:
            return i

    return -1


class AssistantOnlyDataCollator:
    """Pad examples while preserving assistant-only labels."""

    def __init__(self, tokenizer, label_pad_token_id=-100):
        self.tokenizer = tokenizer
        self.label_pad_token_id = label_pad_token_id

    def __call__(self, features):
        labels = [feature["labels"] for feature in features]

        model_features = [
            {
                key: value
                for key, value in feature.items()
                if key != "labels"
            }
            for feature in features
        ]

        batch = self.tokenizer.pad(
            model_features,
            padding=True,
            return_tensors="pt",
        )

        max_length = batch["input_ids"].shape[1]

        padded_labels = []

        for label in labels:
            padding_length = max_length - len(label)

            if self.tokenizer.padding_side == "right":
                padded = label + [self.label_pad_token_id] * padding_length
            else:
                padded = [self.label_pad_token_id] * padding_length + label

            padded_labels.append(padded)

        batch["labels"] = torch.tensor(
            padded_labels,
            dtype=torch.long,
        )

        return batch


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()

    cfg = load_resolved_config(args.config)
    require_file(cfg["train_file"], "Training dataset")
    require_file(cfg["eval_file"], "Evaluation dataset")
    require_model(cfg["model_name"])
    reserve_new_output(cfg["output_dir"])
    text_field = cfg.get("dataset_text_field", "text")
    max_seq_length = int(cfg["training"]["max_seq_length"])

    print(f"Loading tokenizer: {cfg['model_name']}")
    tokenizer = AutoTokenizer.from_pretrained(
        cfg["model_name"],
        use_fast=True,
        trust_remote_code=True,
    )

    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    tokenizer.padding_side = "right"

    quantization_config = make_quantization_config(cfg.get("quantization", {}))
    quantized = quantization_config is not None
    torch_dtype = DTYPES.get(cfg.get("model_dtype", "float16"), torch.float16)

    print(f"Loading model: {cfg['model_name']}")
    model = AutoModelForCausalLM.from_pretrained(
        cfg["model_name"],
        quantization_config=quantization_config,
        torch_dtype=torch_dtype if not quantized else None,
        device_map="auto",
        trust_remote_code=True,
    )

    model.config.use_cache = False

    if quantized:
        print("Preparing model for k-bit training...")
        model = prepare_model_for_kbit_training(model)

    lora = cfg["lora"]
    peft_config = LoraConfig(
        r=lora["r"],
        lora_alpha=lora["alpha"],
        lora_dropout=lora["dropout"],
        target_modules=lora["target_modules"],
        bias="none",
        task_type="CAUSAL_LM",
    )

    print("Applying LoRA...")
    model = get_peft_model(model, peft_config)
    model.print_trainable_parameters()

    print("Loading dataset...")
    dataset = load_dataset(
        "json",
        data_files={
            "train": cfg["train_file"],
            "eval": cfg["eval_file"],
        },
    )

    assistant_marker = "<|assistant|>"

    print("Training mode: ASSISTANT-ONLY LOSS")
    print("Assistant marker:", repr(assistant_marker))

    def tokenize_batch(batch):
        result = {
            "input_ids": [],
            "attention_mask": [],
            "labels": [],
        }

        for raw_text in batch[text_field]:
            text = str(raw_text).rstrip()

            # Split BEFORE tokenization.
            # This avoids tokenizer-dependent marker matching.
            if assistant_marker not in text:
                raise ValueError(
                    "Assistant marker not found in raw sample. "
                    f"Preview: {text[:300]}"
                )

            prompt_text, response_text = text.split(
                assistant_marker,
                1,
            )

            prompt_text = prompt_text + assistant_marker

            # Tokenize prompt and assistant response separately.
            prompt_ids = tokenizer(
                prompt_text,
                add_special_tokens=True,
                padding=False,
                truncation=False,
            )["input_ids"]

            response_ids = tokenizer(
                response_text,
                add_special_tokens=False,
                padding=False,
                truncation=False,
            )["input_ids"]

            # Make sure the assistant response ends with EOS.
            if tokenizer.eos_token_id is not None:
                if (
                    not response_ids
                    or response_ids[-1] != tokenizer.eos_token_id
                ):
                    response_ids.append(
                        tokenizer.eos_token_id
                    )

            # Keep assistant answer and the most recent part
            # of the prompt if sequence is too long.
            if len(response_ids) >= max_seq_length:
                response_ids = response_ids[
                    :max_seq_length - 1
                ]

                if tokenizer.eos_token_id is not None:
                    response_ids.append(
                        tokenizer.eos_token_id
                    )

                prompt_ids = []

            else:
                available_prompt = (
                    max_seq_length
                    - len(response_ids)
                )

                if len(prompt_ids) > available_prompt:
                    prompt_ids = prompt_ids[
                        -available_prompt:
                    ]

            input_ids = (
                prompt_ids
                + response_ids
            )

            attention_mask = [
                1
            ] * len(input_ids)

            # Prompt is ignored by loss.
            # Only assistant response is supervised.
            labels = (
                [-100] * len(prompt_ids)
                + response_ids.copy()
            )

            if not response_ids:
                raise ValueError(
                    "Assistant response is empty."
                )

            result["input_ids"].append(
                input_ids
            )

            result["attention_mask"].append(
                attention_mask
            )

            result["labels"].append(
                labels
            )

        return result

    print("Tokenizing dataset...")
    train_columns = dataset["train"].column_names
    eval_columns = dataset["eval"].column_names

    tokenized_train = dataset["train"].map(
        tokenize_batch,
        batched=True,
        remove_columns=train_columns,
        desc="Tokenizing train",
    )

    tokenized_eval = dataset["eval"].map(
        tokenize_batch,
        batched=True,
        remove_columns=eval_columns,
        desc="Tokenizing eval",
    )

    data_collator = AssistantOnlyDataCollator(
        tokenizer=tokenizer,
    )

    # Sanity check: only assistant response should be supervised.
    first = tokenized_train[0]

    supervised_ids = [
        token_id
        for token_id, label in zip(
            first["input_ids"],
            first["labels"],
        )
        if label != -100
    ]

    print("\n===== ASSISTANT-ONLY SANITY CHECK =====")
    print(
        tokenizer.decode(
            supervised_ids,
            skip_special_tokens=False,
        )
    )
    print("Supervised tokens:", len(supervised_ids))
    print("Total tokens:", len(first["input_ids"]))
    print("=======================================\n")

    training_args = build_training_args(cfg, quantized=quantized)

    trainer = Trainer(
        model=model,
        args=training_args,
        train_dataset=tokenized_train,
        eval_dataset=tokenized_eval,
        data_collator=data_collator,
    )

    print("Starting training...")
    trainer.train()

    output_dir = Path(cfg["output_dir"])
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Saving adapter/checkpoint to: {output_dir}")
    trainer.save_model(str(output_dir))
    tokenizer.save_pretrained(str(output_dir))

    print("Training complete.")


if __name__ == "__main__":
    main()
