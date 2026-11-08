#include "llama.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s MODEL.gguf PROMPT\n", argv[0]);
        return 1;
    }

    const char *model_path = argv[1];
    const char *prompt = argv[2];
    const int max_tokens = 64;
    int status = 1;

    struct llama_model *model = NULL;
    struct llama_context *ctx = NULL;
    struct llama_sampler *sampler = NULL;
    llama_token *tokens = NULL;

    llama_backend_init();

    struct llama_model_params mp =
        llama_model_default_params();
    mp.n_gpu_layers = 0;

    model = llama_model_load_from_file(model_path, mp);
    if (!model) {
        fprintf(stderr, "Model loading failed\n");
        goto cleanup;
    }

    const struct llama_vocab *vocab =
        llama_model_get_vocab(model);

    int n_prompt = llama_tokenize(
        vocab, prompt, (int32_t)strlen(prompt),
        NULL, 0, true, true
    );

    if (n_prompt >= 0) {
        fprintf(stderr, "Unexpected tokenizer result\n");
        goto cleanup;
    }

    n_prompt = -n_prompt;

    if (n_prompt > 512) {
        fprintf(stderr, "Prompt exceeds batch limit\n");
        goto cleanup;
    }

    tokens = malloc((size_t)n_prompt * sizeof(*tokens));
    if (!tokens) {
        goto cleanup;
    }

    int actual = llama_tokenize(
        vocab, prompt, (int32_t)strlen(prompt),
        tokens, n_prompt, true, true
    );

    if (actual != n_prompt) {
        fprintf(stderr, "Tokenization failed\n");
        goto cleanup;
    }

    struct llama_context_params cp =
        llama_context_default_params();

    cp.n_ctx = 1024;
    cp.n_batch = 512;

    ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        fprintf(stderr, "Context initialization failed\n");
        goto cleanup;
    }

    sampler = llama_sampler_init_greedy();
    if (!sampler) {
        goto cleanup;
    }

    struct llama_batch batch =
        llama_batch_get_one(tokens, n_prompt);

    if (llama_decode(ctx, batch) != 0) {
        fprintf(stderr, "Prompt evaluation failed\n");
        goto cleanup;
    }

    printf("\n===== GENERATED RESPONSE =====\n");

    for (int i = 0; i < max_tokens; ++i) {
        llama_token token =
            llama_sampler_sample(sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        char piece[8192];
        int length = llama_token_to_piece(
            vocab, token, piece, sizeof(piece),
            0, true
        );

        if (length < 0) {
            fprintf(stderr, "Token conversion failed\n");
            goto cleanup;
        }

        fwrite(piece, 1, (size_t)length, stdout);
        fflush(stdout);

        batch = llama_batch_get_one(&token, 1);

        if (llama_decode(ctx, batch) != 0) {
            fprintf(stderr, "Generation failed\n");
            goto cleanup;
        }
    }

    printf("\n");
    status = 0;

cleanup:
    free(tokens);
    if (sampler) llama_sampler_free(sampler);
    if (ctx) llama_free(ctx);
    if (model) llama_model_free(model);
    llama_backend_free();

    return status;
}
