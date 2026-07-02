package com.tonepilot.starter.bridge;

import com.tonepilot.application.agent.*;
import com.tonepilot.application.config.*;
import com.tonepilot.application.controller.*;
import com.tonepilot.application.lightroom.*;
import com.tonepilot.domain.agent.*;
import com.tonepilot.repository.lightroom.*;
import com.tonepilot.infrastructure.admin.*;
import com.tonepilot.infrastructure.config.*;
import com.tonepilot.infrastructure.lightroom.filesystem.*;
import com.tonepilot.infrastructure.lightroom.repository.*;
import com.tonepilot.infrastructure.model.*;
import com.tonepilot.infrastructure.observability.*;
import com.tonepilot.starter.*;





import com.tonepilot.infrastructure.observability.RuntimeTraceLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LightroomToolServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void writesApplyJobAsExecutableLuaChunk() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getBridge().setRoot(tempDir.toString());
        properties.getBridge().setLightroomRoot(tempDir.toString());

        FileLightroomToolRepository repository = new FileLightroomToolRepository();
        ReflectionTestUtils.setField(repository, "properties", properties);
        ReflectionTestUtils.setField(repository, "traceLogger", mock(RuntimeTraceLogger.class));

        repository.applyDevelopSettings(Map.of("Exposure2012", 0.2));

        Path job = Files.list(tempDir.resolve("apply-jobs"))
                .filter(path -> path.getFileName().toString().endsWith(".lua"))
                .findFirst()
                .orElseThrow();
        String content = Files.readString(job);

        assertThat(content).startsWith("return {");
        assertThat(content).contains("[\"developSettings\"]=");
        assertThat(content).contains("[\"Exposure2012\"]=0.2");
    }

    @Test
    void writesLocalAdjustmentPlanIntoApplyJobWithoutGlobalSettings() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getBridge().setRoot(tempDir.toString());
        properties.getBridge().setLightroomRoot(tempDir.toString());

        FileLightroomToolRepository repository = new FileLightroomToolRepository();
        ReflectionTestUtils.setField(repository, "properties", properties);
        ReflectionTestUtils.setField(repository, "traceLogger", mock(RuntimeTraceLogger.class));

        Map<String, Object> skyMask = Map.of(
                "type", "linear_gradient",
                "target", "天空",
                "coordinateSpace", "normalized_crop",
                "region", Map.of("x", 0.0, "y", 0.0, "w", 1.0, "h", 0.42),
                "settings", Map.of("Exposure2012", -0.25, "Highlights2012", -18)
        );

        repository.applyAdjustments(Map.of(), List.of(skyMask));

        Path job = Files.list(tempDir.resolve("apply-jobs"))
                .filter(path -> path.getFileName().toString().endsWith(".lua"))
                .findFirst()
                .orElseThrow();
        String content = Files.readString(job);

        assertThat(content).contains("[\"localAdjustments\"]=");
        assertThat(content).contains("[\"type\"]=\"linear_gradient\"");
        assertThat(content).contains("[\"target\"]=\"天空\"");
        assertThat(content).contains("[\"Exposure2012\"]=-0.25");
        assertThat(content).contains("[\"Highlights2012\"]=-18");
    }


    @Test
    void writesExpandedGlobalColorCurveAndLinearMaskIntoApplyJob() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getBridge().setRoot(tempDir.toString());
        properties.getBridge().setLightroomRoot(tempDir.toString());

        FileLightroomToolRepository repository = new FileLightroomToolRepository();
        ReflectionTestUtils.setField(repository, "properties", properties);
        ReflectionTestUtils.setField(repository, "traceLogger", mock(RuntimeTraceLogger.class));

        Map<String, Object> skyMask = Map.of(
                "type", "linear_gradient",
                "target", "天空",
                "coordinateSpace", "normalized_crop",
                "region", Map.of("x", 0.0, "y", 0.0, "w", 1.0, "h", 0.45),
                "feather", 0.65,
                "settings", Map.of("Exposure2012", -0.35, "Dehaze", 20)
        );

        repository.applyAdjustments(Map.of(
                "HueAdjustmentBlue", -8,
                "SaturationAdjustmentBlue", 18,
                "LuminanceAdjustmentBlue", -12,
                "BluePrimaryHue", -6,
                "BluePrimarySaturation", 12,
                "ParametricHighlights", -18,
                "ParametricLights", 8,
                "ToneCurveName2012", "Custom",
                "ToneCurvePV2012", List.of("0, 0", "32, 24", "128, 132", "224, 232", "255, 255")
        ), List.of(skyMask));

        Path job = Files.list(tempDir.resolve("apply-jobs"))
                .filter(path -> path.getFileName().toString().endsWith(".lua"))
                .findFirst()
                .orElseThrow();
        String content = Files.readString(job);

        assertThat(content).contains("[\"HueAdjustmentBlue\"]=-8");
        assertThat(content).contains("[\"SaturationAdjustmentBlue\"]=18");
        assertThat(content).contains("[\"LuminanceAdjustmentBlue\"]=-12");
        assertThat(content).contains("[\"BluePrimaryHue\"]=-6");
        assertThat(content).contains("[\"ParametricHighlights\"]=-18");
        assertThat(content).contains("[\"ParametricLights\"]=8");
        assertThat(content).contains("[\"ToneCurveName2012\"]=\"Custom\"");
        assertThat(content).contains("[\"ToneCurvePV2012\"]={[1]=\"0, 0\",[2]=\"32, 24\",[3]=\"128, 132\",[4]=\"224, 232\",[5]=\"255, 255\",");
        assertThat(content).contains("[\"type\"]=\"linear_gradient\"");
        assertThat(content).contains("[\"feather\"]=0.65");
        assertThat(content).contains("[\"Dehaze\"]=20");
    }


    @Test
    void applyStatusIncludesLocalMaskPlacementResultFields() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getBridge().setRoot(tempDir.toString());
        properties.getBridge().setLightroomRoot(tempDir.toString());
        Files.createDirectories(tempDir.resolve("apply-results"));
        Files.writeString(tempDir.resolve("apply-results/job-1.result"), String.join("\n",
                "success=true",
                "message=ok",
                "previewUrl=/files/job-1.jpg",
                "localGuideMessage=已打开线性渐变蒙版",
                "localAdjustmentCount=1",
                "localMaskCreatedCount=0",
                "localMaskNeedsUserPlacement=true"
        ));

        FileLightroomToolRepository repository = new FileLightroomToolRepository();
        ReflectionTestUtils.setField(repository, "properties", properties);
        ReflectionTestUtils.setField(repository, "traceLogger", mock(RuntimeTraceLogger.class));

        Map<String, Object> status = repository.applyStatus("job-1");

        assertThat(status).containsEntry("localGuideMessage", "已打开线性渐变蒙版");
        assertThat(status).containsEntry("localAdjustmentCount", "1");
        assertThat(status).containsEntry("localMaskCreatedCount", "0");
        assertThat(status).containsEntry("localMaskNeedsUserPlacement", "true");
    }


    @Test
    void applyStatusMarksLocalMaskJobsThatNeedUserPlacement() throws Exception {
        RuntimeProperties properties = new RuntimeProperties();
        properties.getBridge().setRoot(tempDir.toString());
        properties.getBridge().setLightroomRoot(tempDir.toString());
        Files.createDirectories(tempDir.resolve("apply-results"));
        Files.writeString(tempDir.resolve("apply-results/job-local.result"), String.join("\n",
                "success=true",
                "message=线性渐变蒙版需要在 Lightroom 中确认位置",
                "previewUrl=/files/job-local.jpg",
                "localGuideMessage=已打开 Masking 面板，请确认天空线性渐变范围",
                "localAdjustmentCount=1",
                "localMaskCreatedCount=0",
                "localMaskNeedsUserPlacement=true"
        ));

        FileLightroomToolRepository repository = new FileLightroomToolRepository();
        ReflectionTestUtils.setField(repository, "properties", properties);
        ReflectionTestUtils.setField(repository, "traceLogger", mock(RuntimeTraceLogger.class));

        Map<String, Object> status = repository.applyStatus("job-local");

        assertThat(status).containsEntry("localMaskStatus", "needs_user_placement");
        assertThat(status).containsEntry("localMaskStatusText", "局部蒙版已下发到 Lightroom，但需要你在蒙版面板中确认区域位置。");
        assertThat(status).containsEntry("localMaskNeedsUserPlacement", "true");
    }

}
