"""
SpeakIn - 使用 ExecuTorch 导出 whisper-small 模型为 .pte 格式

使用方法:
  1. 安装依赖:
     pip install torch torchaudio executorch

  2. 运行导出:
     python export_whisper_pte.py --output-dir ./exported_whisper

  3. 将导出的文件推送到手机:
     adb push ./exported_whisper/ /data/data/com.speakin.app/files/whisper/
"""

import argparse
import json
import os
import sys

import torch
import torchaudio


# ============================================================
# 1. 加载 whisper-small 并封装导出模块
# ============================================================

class WhisperEncoderWrapper(torch.nn.Module):
    """封装 whisper 的 encoder：audio → encoder_hidden_states"""

    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder
        self.eval()

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        # mel shape: (1, 80, time_frames)
        return self.encoder(mel)


class WhisperDecoderWrapper(torch.nn.Module):
    """封装 whisper 的 decoder：tokens + encoder_output → logits"""

    def __init__(self, decoder):
        super().__init__()
        self.decoder = decoder
        self.eval()

    def forward(
        self, tokens: torch.Tensor, encoder_output: torch.Tensor
    ) -> torch.Tensor:
        # tokens shape: (1, seq_len)
        # encoder_output shape: (1, time_frames, 768)
        return self.decoder(tokens, encoder_output)


# ============================================================
# 2. 导出为 ExecuTorch 格式
# ============================================================

def export_whisper(output_dir: str, model_name: str = "tiny"):
    """
    导出 whisper 模型为 ExecuTorch .pte 文件。

    Args:
        output_dir: 输出目录
        model_name: whisper 模型大小 (tiny/base/small/medium/large)
    """
    os.makedirs(output_dir, exist_ok=True)

    print(f"[1/4] 加载 whisper-{model_name} ...")
    whisper = torchaudio.models.whisper_builder(model_name)
    whisper.eval()

    # 确定模型的维度参数
    # whisper-small: d_model=768, encoder_attention_heads=12, decoder_attention_heads=12
    # whisper-tiny:  d_model=384, encoder_attention_heads=6,  decoder_attention_heads=6
    d_model = whisper.encoder.d_model if hasattr(whisper.encoder, "d_model") else 384
    n_mels = 80
    n_audio_ctx = 1500  # 最大音频帧（30秒 @ 50帧/秒）
    n_text_ctx = 448    # 最大文本 token 数

    # Encoder 输入：mel 频谱 (1, 80, 3000) 对应 30 秒音频
    encoder_wrapper = WhisperEncoderWrapper(whisper.encoder)
    mel_example = torch.randn(1, n_mels, n_audio_ctx)

    # Decoder 输入：token IDs + encoder 输出
    decoder_wrapper = WhisperDecoderWrapper(whisper.decoder)
    tokens_example = torch.randint(0, 51865, (1, 1))  # 起始 token
    encoder_output_example = torch.randn(1, n_audio_ctx // 2, d_model)

    print(f"[2/4] 导出 encoder → {output_dir}/whisper_encoder.pte")
    _export_to_pte(encoder_wrapper, mel_example, os.path.join(output_dir, "whisper_encoder.pte"))

    print(f"[3/4] 导出 decoder → {output_dir}/whisper_decoder.pte")
    _export_to_pte(
        decoder_wrapper,
        (tokens_example, encoder_output_example),
        os.path.join(output_dir, "whisper_decoder.pte"),
    )

    print(f"[4/4] 生成配置文件 → {output_dir}/whisper_config.json")
    config = {
        "model": f"whisper-{model_name}",
        "n_mels": n_mels,
        "n_audio_ctx": n_audio_ctx,
        "n_text_ctx": n_text_ctx,
        "d_model": d_model,
        "sample_rate": 16000,
        "fft_size": 400,
        "hop_length": 160,
        "window_length": 400,
        "n_mel_bins": 80,
        "language": "multilingual",
        "sot_token": 50258,      # <|startoftranscript|>
        "eot_token": 50257,      # <|endoftext|>
        "notimestamps_token": 50363,
        "transcribe_token": 50362,
    }
    with open(os.path.join(output_dir, "whisper_config.json"), "w") as f:
        json.dump(config, f, indent=2)

    print(f"\n✅ 导出完成！文件在: {output_dir}/")
    print(f"   文件列表:")
    for fname in os.listdir(output_dir):
        fpath = os.path.join(output_dir, fname)
        size_mb = os.path.getsize(fpath) / (1024 * 1024)
        print(f"     - {fname} ({size_mb:.1f} MB)")


def _export_to_pte(module, example_input, output_path: str):
    """使用 ExecuTorch 的 export + to_edge 导出为 .pte"""
    try:
        from executorch import exir
        from executorch.exir.backend.backend_api import to_backend
    except ImportError:
        print("=" * 60)
        print("需要安装 executorch：pip install executorch")
        print("或者从源码编译：https://github.com/pytorch/executorch")
        print("=" * 60)
        sys.exit(1)

    with torch.no_grad():
        # Export to Edge dialect
        exported = torch.export.export(module, (example_input,))
        edge_program = exir.to_edge(exported)
        edge_program_executorch = edge_program.to_executorch()

        with open(output_path, "wb") as f:
            f.write(edge_program_executorch.buffer)

    print(f"    ✅ {os.path.basename(output_path)} 导出成功")


# ============================================================
# 3. 导出 tokenizer 数据
# ============================================================

def export_tokenizer(output_dir: str, model_name: str = "tiny"):
    """
    导出 whisper tokenizer 的 vocab，用于 Android 端解码。
    """
    print(f"\n[可选] 导出 tokenizer → {output_dir}/tokenizer.json")

    try:
        from transformers import WhisperTokenizer as HFWhisperTokenizer
        tokenizer = HFWhisperTokenizer.from_pretrained(f"openai/whisper-{model_name}")
        tokenizer.save_pretrained(output_dir)
        print(f"    ✅ tokenizer 已保存到 {output_dir}/")
    except ImportError:
        print("    ⚠️ 未安装 transformers，跳过 tokenizer 导出。")
        print("   建议: pip install transformers")
        print("   也可从 HuggingFace 手动下载 tokenizer.json:")
        print(f"   https://huggingface.co/openai/whisper-{model_name}/raw/main/tokenizer.json")


# ============================================================
# 4. 主入口
# ============================================================

def main():
    parser = argparse.ArgumentParser(description="导出 whisper-small 为 ExecuTorch .pte 格式")
    parser.add_argument(
        "--output-dir",
        default="./exported_whisper",
        help="输出目录 (默认: ./exported_whisper)",
    )
    parser.add_argument(
        "--model",
        default="small",
        choices=["tiny", "base", "small", "medium"],
        help="whisper 模型大小 (默认: small)",
    )
    parser.add_argument(
        "--export-tokenizer",
        action="store_true",
        help="同时导出 tokenizer (需要安装 transformers)",
    )
    args = parser.parse_args()

    export_whisper(args.output_dir, args.model)

    if args.export_tokenizer:
        export_tokenizer(args.output_dir, args.model)

    print("\n💡 推送到手机的命令:")
    print(f"   adb push {args.output_dir}/ /data/data/com.speakin.app/files/whisper/")


if __name__ == "__main__":
    main()
