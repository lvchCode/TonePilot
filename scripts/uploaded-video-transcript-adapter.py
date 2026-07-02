#!/usr/bin/env python3
"""TonePilot 上传视频转字幕适配器。

输入来自环境变量：
- TONEPILOT_VIDEO_PATH：管理端保存的视频文件路径
- TONEPILOT_VIDEO_TITLE / AUTHOR / NOTES：素材元信息

脚本优先复用 video-to-subtitle-summary skill 的 faster-whisper 转写脚本。
需要系统安装 ffmpeg，并安装该 skill 的 Python 依赖。
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def skill_dir() -> Path:
    configured = os.getenv("VIDEO_TO_SUBTITLE_SUMMARY_SKILL_DIR", "").strip()
    candidates = []
    if configured:
        candidates.append(Path(configured).expanduser())
    home = Path.home()
    candidates.extend([
        home / ".codex" / "skills" / "video-to-subtitle-summary",
        home / ".codex" / "skills" / "video-to-subtitle-summary-skill",
    ])
    for candidate in candidates:
        if (candidate / "scripts" / "transcribe_faster_whisper.py").exists():
            return candidate
    raise SystemExit("未找到 video-to-subtitle-summary skill，请配置 VIDEO_TO_SUBTITLE_SUMMARY_SKILL_DIR。")


def run(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, capture_output=True, check=False)


def main() -> int:
    video_path = Path(os.getenv("TONEPILOT_VIDEO_PATH", "")).expanduser()
    if not video_path.exists():
        print(f"视频文件不存在：{video_path}", file=sys.stderr)
        return 2
    if not shutil.which("ffmpeg"):
        print("未安装 ffmpeg，无法从视频提取音频。", file=sys.stderr)
        return 2

    title = os.getenv("TONEPILOT_VIDEO_TITLE", "上传抖音调色教程")
    author = os.getenv("TONEPILOT_VIDEO_AUTHOR", "未知作者")
    notes = os.getenv("TONEPILOT_VIDEO_NOTES", "")
    transcribe_script = skill_dir() / "scripts" / "transcribe_faster_whisper.py"

    with tempfile.TemporaryDirectory(prefix="tonepilot-video-") as tmp:
        audio_path = Path(tmp) / "audio.wav"
        ffmpeg = run([
            "ffmpeg", "-y", "-i", str(video_path),
            "-vn", "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", str(audio_path)
        ])
        if ffmpeg.returncode != 0:
            print(ffmpeg.stderr.strip() or ffmpeg.stdout.strip(), file=sys.stderr)
            return ffmpeg.returncode

        transcript = run([sys.executable, str(transcribe_script), str(audio_path)])
        if transcript.returncode != 0:
            print(transcript.stderr.strip() or transcript.stdout.strip(), file=sys.stderr)
            return transcript.returncode

    print(f"视频标题：{title}")
    print(f"作者：{author}")
    if notes:
        print(f"管理员备注：{notes}")
    print("")
    print("字幕转写：")
    print(transcript.stdout.strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
