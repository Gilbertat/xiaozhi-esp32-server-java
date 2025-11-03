#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import sys
import whisper
import numpy as np
import soundfile as sf
import importlib.util
import warnings
warnings.filterwarnings("ignore")

def detect_lang(audio_path: str) -> str:
    try:
        # 读取音频并转换为 float32
        audio, sr = sf.read(audio_path)
        if len(audio.shape) > 1:
            audio = np.mean(audio, axis=1)
        audio = audio.astype(np.float32)

        # Whisper 轻量识别
        model = whisper.load_model("tiny", device="cuda" if whisper.torch.cuda.is_available() else "cpu")
        result = model.transcribe(audio, fp16=False)
        text = result.get("text", "").strip()

        if not text:
            print("unknown")
            return

        # 检查 pycld3 是否可用
        if importlib.util.find_spec("pycld3") is not None:
            import pycld3
            detection = pycld3.get_language(text)
            if detection and detection.is_reliable:
                print(detection.language)
                return
            else:
                print("unknown")
                return
        else:
            # 回退：使用 Whisper 自带语言检测
            mel = whisper.log_mel_spectrogram(whisper.pad_or_trim(audio)).to(model.device)
            _, probs = model.detect_language(mel)
            lang = max(probs, key=probs.get)
            print(lang)
            return

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        print("unknown")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: detect_lang_advanced.py <audio_path>")
        sys.exit(1)
    detect_lang(sys.argv[1])