"""
Export Whisper tiny encoder and decoder for CPU (XNNPACK) inference.
Produces two .pte files:
  - whisper_encoder.pte: mel spectrogram -> encoder hidden states
  - whisper_decoder.pte: autoregressive decoder (full sequence, no KV cache)

Usage: python scripts/export_whisper_cpu.py
"""
import sys
sys.path.insert(0, "D:/github/executorch")

import torch
from transformers import AutoModelForSpeechSeq2Seq
from executorch.exir import EdgeCompileConfig, to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

OUT_DIR = "D:/github/SpeakIn/whisper_models"


def export_encoder():
    print("Loading whisper-tiny from HuggingFace...")
    model = AutoModelForSpeechSeq2Seq.from_pretrained("openai/whisper-tiny")
    # Save tokenizer for deployment
    from transformers import AutoTokenizer
    tokenizer = AutoTokenizer.from_pretrained("openai/whisper-tiny")
    tokenizer.save_pretrained(OUT_DIR)
    print(f"Tokenizer saved to {OUT_DIR}")

    encoder = model.get_encoder()

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, enc):
            super().__init__()
            self.encoder = enc

        def forward(self, input_features):
            return self.encoder(input_features).last_hidden_state

    wrapped = EncoderWrapper(encoder)
    wrapped.eval()
    example = torch.randn(1, 80, 3000)

    print("Exporting encoder...")
    with torch.no_grad():
        ep = torch.export.export(wrapped, (example,), strict=True)

    edge = to_edge_transform_and_lower(
        ep,
        partitioner=[XnnpackPartitioner()],
        compile_config=EdgeCompileConfig(_check_ir_validity=False),
    )
    exec_prog = edge.to_executorch()
    path = f"{OUT_DIR}/whisper_encoder.pte"
    with open(path, "wb") as f:
        exec_prog.write_to_file(f)
    print(f"Encoder exported: {path}")
    return model


def export_decoder(model):
    decoder = model.get_decoder()
    proj_out = model.proj_out
    MAX_SEQ = 128  # max output tokens

    class DecoderWrapper(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.decoder = decoder
            self.proj_out = proj_out

        def forward(self, input_ids, attention_mask, encoder_hidden_states):
            # input_ids: [1, seq_len] token IDs
            # attention_mask: [seq_len] float mask (1=valid, 0=pad)
            # encoder_hidden_states: [1, 1500, 384]
            outputs = self.decoder(
                input_ids=input_ids,
                attention_mask=attention_mask,
                encoder_hidden_states=encoder_hidden_states,
                use_cache=False,
            )
            return self.proj_out(outputs[0])

    wrapped = DecoderWrapper()
    wrapped.eval()

    # Example inputs — use MAX_SEQ to export with fixed max length
    # This avoids ExecuTorch "static tensor" errors when sequence length changes
    input_ids = torch.zeros((1, MAX_SEQ), dtype=torch.long)
    input_ids[0, 0] = 50258  # SOT
    input_ids[0, 1] = 50259  # EN
    input_ids[0, 2] = 50359  # TRANSCRIBE
    input_ids[0, 3] = 50363  # NOTIMESTAMPS
    encoder_hidden = torch.randn(1, 1500, 384)
    attn_mask = torch.zeros((1, MAX_SEQ), dtype=torch.float32)
    attn_mask[0, 0] = 1.0
    attn_mask[0, 1] = 1.0
    attn_mask[0, 2] = 1.0
    attn_mask[0, 3] = 1.0

    print("Exporting decoder...")
    with torch.no_grad():
        ep = torch.export.export(
            wrapped,
            (input_ids, attn_mask, encoder_hidden),
            strict=True,
        )

    edge = to_edge_transform_and_lower(
        ep,
        partitioner=[XnnpackPartitioner()],
        compile_config=EdgeCompileConfig(_check_ir_validity=False),
    )
    exec_prog = edge.to_executorch()
    path = f"{OUT_DIR}/whisper_decoder.pte"
    with open(path, "wb") as f:
        exec_prog.write_to_file(f)
    print(f"Decoder exported: {path}")


if __name__ == "__main__":
    model = export_encoder()
    export_decoder(model)
    print("Done!")
