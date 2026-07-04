package com.tonepilot.service;

import com.tonepilot.infrastructure.knowledge.douyin.VideoTranscriptService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VideoTranscriptServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void transcribesUploadedVideoWithLocalWhisperCppCommand() throws Exception {
        Path video = tempDir.resolve("douyin.mp4");
        Files.writeString(video, "fake video", StandardCharsets.UTF_8);

        Path fakeFfmpeg = executable("fake-ffmpeg.sh", """
                #!/usr/bin/env bash
                set -e
                audio_path="${@: -1}"
                echo audio > "$audio_path"
                """);
        Path fakeWhisper = executable("fake-whisper.sh", """
                #!/usr/bin/env bash
                set -e
                output_prefix=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "-of" ]; then
                    shift
                    output_prefix="$1"
                  fi
                  shift || true
                done
                cat > "${output_prefix}.srt" <<SRT\n1\n00:00:00,000 --> 00:00:03,500\n先压低高光。\n\n2\n00:00:03,500 --> 00:00:07,000\n再提高阴影，蓝色饱和度降低。\nSRT\n
                """);

        VideoTranscriptService service = new VideoTranscriptService();
        ReflectionTestUtils.setField(service, "ffmpegBin", fakeFfmpeg.toString());
        ReflectionTestUtils.setField(service, "asrProvider", "whisper-cpp");
        ReflectionTestUtils.setField(service, "whisperCppBin", fakeWhisper.toString());
        ReflectionTestUtils.setField(service, "whisperModel", tempDir.resolve("ggml-small.bin").toString());
        ReflectionTestUtils.setField(service, "language", "zh");
        ReflectionTestUtils.setField(service, "timeoutSeconds", 10L);
        ReflectionTestUtils.setField(service, "transcriptOverride", "");

        String transcript = service.transcribeVideo(video, "douyin.mp4", "蓝调教程", "调色博主", "夜景蓝调");

        assertThat(transcript).contains("视频标题：蓝调教程");
        assertThat(transcript).contains("作者：调色博主");
        assertThat(transcript).contains("文件名：douyin.mp4");
        assertThat(transcript).contains("管理员备注：夜景蓝调");
        assertThat(transcript).contains("时间戳字幕：");
        assertThat(transcript).contains("[00:00:00.000-00:00:03.500] 先压低高光。");
        assertThat(transcript).contains("[00:00:03.500-00:00:07.000] 再提高阴影，蓝色饱和度降低。");
    }

    @Test
    void keepsTranscriptOverrideForFastIntegrationTests() throws Exception {
        Path video = tempDir.resolve("douyin.mp4");
        Files.writeString(video, "fake video", StandardCharsets.UTF_8);

        VideoTranscriptService service = new VideoTranscriptService();
        ReflectionTestUtils.setField(service, "transcriptOverride", "测试字幕：降低高光。");

        String transcript = service.transcribeVideo(video, "douyin.mp4", "测试教程", "作者", "");

        assertThat(transcript).contains("视频标题：测试教程");
        assertThat(transcript).contains("时间戳字幕：");
        assertThat(transcript).contains("[无时间戳] 测试字幕：降低高光。");
    }

    private Path executable(String fileName, String content) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, content, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return script;
    }
}
