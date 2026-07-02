#!/usr/bin/env python3
"""TonePilot 抖音字幕导入适配器。

输入来自 Java 进程注入的环境变量：
- TONEPILOT_DOUYIN_URL：抖音作品链接或分享链接中解析出的 URL
- TONEPILOT_DOUYIN_SHARE_TEXT：用户粘贴的完整分享文案
- TONEPILOT_DOUYIN_TITLE / AUTHOR / NOTES：管理端填写的元数据

输出到 stdout 的内容会被 TonePilot 当成“字幕/素材正文”写入知识库。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


URL_PATTERN = re.compile(r"https?://\S+")
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
)


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def extract_url(raw: str) -> str:
    match = URL_PATTERN.search(raw)
    if not match:
        return raw.strip()
    return match.group(0).rstrip("，,。！？!")


def post_json(url: str, payload: dict[str, object], headers: dict[str, str]) -> dict[str, object]:
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        headers={
            "Content-Type": "application/json",
            "User-Agent": USER_AGENT,
            **headers,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"AI Douyin 请求失败：HTTP {exc.code} {detail}") from exc


def resolve_skill_dir(explicit: str) -> Path:
    candidates = [
        explicit,
        env("VIDEO_TO_SUBTITLE_SUMMARY_SKILL_DIR"),
        str(Path.home() / ".codex/skills/video-to-subtitle-summary"),
        str(Path.home() / ".claude/skills/video-to-subtitle-summary"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate).expanduser().resolve()
        if (path / "scripts/download_video_candidates.py").exists():
            return path
    raise RuntimeError("未找到 video-to-subtitle-summary-skill，请设置 VIDEO_TO_SUBTITLE_SUMMARY_SKILL_DIR")


def fetch_download_candidates(video_url: str) -> dict[str, object]:
    provider = env("VIDEO_INFO_PROVIDER", "ai-douyin")
    if provider != "ai-douyin":
        raise RuntimeError("当前适配器只内置 AI Douyin 解析；TikHub 可通过自定义 TONEPILOT_DOUYIN_COMMAND 接入。")
    api_key = env("AI_DOUYIN_API_KEY")
    if not api_key or api_key == "your_ai_douyin_api_key":
        raise RuntimeError("未配置 AI_DOUYIN_API_KEY，无法解析抖音视频下载地址。")
    base_url = env("AI_DOUYIN_API_BASE", "https://ai-douyin.top9.cc").rstrip("/")
    return post_json(
        f"{base_url}/api/v1/video/download-url",
        {"url": video_url},
        {"X-API-Key": api_key},
    )


def run(command: list[str]) -> str:
    result = subprocess.run(command, check=False, text=True, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return result.stdout.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skill-dir", default="")
    parser.add_argument("--work-dir", default="")
    parser.add_argument("--language", default=env("TONEPILOT_DOUYIN_LANGUAGE", "zh"))
    args = parser.parse_args()

    try:
        skill_dir = resolve_skill_dir(args.skill_dir)
        load_dotenv(skill_dir / ".env")

        raw = env("TONEPILOT_DOUYIN_URL") or env("TONEPILOT_DOUYIN_SHARE_TEXT")
        video_url = extract_url(raw)
        if not video_url:
            raise RuntimeError("没有收到抖音视频链接。")

        work_root = Path(args.work_dir).expanduser() if args.work_dir else Path(tempfile.mkdtemp(prefix="tonepilot-douyin-"))
        work_root.mkdir(parents=True, exist_ok=True)

        response_json = work_root / "download-response.json"
        video_path = work_root / "video.mp4"
        audio_path = work_root / "audio.wav"
        transcript_dir = work_root / "transcript"

        response = fetch_download_candidates(video_url)
        response_json.write_text(json.dumps(response, ensure_ascii=False, indent=2), encoding="utf-8")

        run([
            sys.executable,
            str(skill_dir / "scripts/download_video_candidates.py"),
            "--response-json",
            str(response_json),
            "--output",
            str(video_path),
        ])

        run([
            "ffmpeg",
            "-y",
            "-i",
            str(video_path),
            "-vn",
            "-ac",
            "1",
            "-ar",
            "16000",
            str(audio_path),
        ])

        whisper_python = env("FW_PYTHON") or sys.executable
        run([
            whisper_python,
            str(skill_dir / "scripts/transcribe_faster_whisper.py"),
            str(audio_path),
            "--output-dir",
            str(transcript_dir),
            "--language",
            args.language,
        ])

        text_path = transcript_dir / "text.txt"
        transcript = text_path.read_text(encoding="utf-8").strip()
        if not transcript:
            raise RuntimeError("ASR 完成但没有生成字幕文本。")

        print(f"视频链接：{video_url}")
        if env("TONEPILOT_DOUYIN_TITLE"):
            print(f"视频标题：{env('TONEPILOT_DOUYIN_TITLE')}")
        if env("TONEPILOT_DOUYIN_AUTHOR"):
            print(f"作者：{env('TONEPILOT_DOUYIN_AUTHOR')}")
        print()
        print("字幕转写：")
        print(transcript)
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI adapter must surface actionable errors
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
