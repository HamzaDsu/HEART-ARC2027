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

    def tokenize_batch(batch):
        texts = batch[text_field]
        texts = [str(x) for x in texts]
        return tokenizer(
            texts,
            truncation=True,
            max_length=max_seq_length,
            padding=False,
        )

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

    data_collator = DataCollatorForLanguageModeling(
        tokenizer=tokenizer,
        mlm=False,
    )

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
