local LrApplication = import "LrApplication"
local LrApplicationView = import "LrApplicationView"
local LrDevelopController = import "LrDevelopController"
local LrFileUtils = import "LrFileUtils"
local LrFunctionContext = import "LrFunctionContext"
local LrExportSession = import "LrExportSession"
local LrPathUtils = import "LrPathUtils"
local LrTasks = import "LrTasks"

local Config = dofile(LrPathUtils.child(_PLUGIN.path, "BridgeConfig.lua"))

local BridgeWorker = {
    running = false
}

local WORKER_BUILD = 25
local lastPreviewKey = ""
local lastPreviewAt = 0
local lastBaselinePhotoKey = ""
local lastBaselinePreviewUrl = ""
local lastBaselinePreviewPath = ""
local lastMetadataDebugKey = ""
local lastMetadataDebugAt = 0
local lastMetadataDebug = nil

function BridgeWorker.start()
    if BridgeWorker.running then
        return
    end
    BridgeWorker.running = true
    ensureDirectories()
    writeDiagnostic("worker-started.txt", "TonePilot Bridge Worker started at " .. tostring(os.time()))
    writeHeartbeat()
    LrTasks.startAsyncTask(function()
        LrFunctionContext.callWithContext("TonePilot Bridge Worker", function()
            writeDiagnostic("worker-task.txt", "TonePilot Bridge Worker LrTask started at " .. tostring(os.time()))
            while BridgeWorker.running do
                local ok, errorMessage = LrTasks.pcall(function()
                    writeHeartbeat()
                    writeSelectedPhotoState()
                    processApplyJobs()
                end)
                if not ok then
                    writeDiagnostic("plugin-error.txt", errorMessage)
                end
                LrTasks.sleep(Config.pollSeconds)
            end
        end)
    end, "TonePilot Bridge Worker")
end

function BridgeWorker.stop()
    BridgeWorker.running = false
end

function ensureDirectories()
    createDirectory(Config.bridgeRoot)
    createDirectory(path("apply-jobs"))
    createDirectory(path("processing"))
    createDirectory(path("results"))
    createDirectory(path("apply-results"))
end

function processApplyJobs()
    local files = LrFileUtils.files(path("apply-jobs"))
    for file in files do
        if string.match(file, "%.lua$") then
            processApplyJob(file)
        end
    end
end

function processApplyJob(jobPath)
    local processingPath = moveToProcessing(jobPath)
    local ok, jobOrError = pcall(function()
        local loader = assert(loadfile(processingPath))
        return loader()
    end)

    if not ok then
        writeApplyResultFromPath(processingPath, false, "读取 TonePilot 应用任务失败：" .. tostring(jobOrError))
        LrFileUtils.delete(processingPath)
        return
    end

    local job = jobOrError
    local success, message = LrTasks.pcall(function()
        applyDevelopSettingsJob(job)
    end)

    if success then
        writeApplyResult(job, true, applySuccessMessage(job))
    else
        writeApplyResult(job, false, "Lightroom 应用参数失败：" .. tostring(message))
    end
    LrFileUtils.delete(processingPath)
end

function moveToProcessing(jobPath)
    local processingPath = LrPathUtils.child(path("processing"), LrPathUtils.leafName(jobPath))
    local moved = LrFileUtils.move(jobPath, processingPath)
    if moved == nil then
        return jobPath
    end
    return processingPath
end

function applySuccessMessage(job)
    local parts = {}
    if tableHasEntries(job.developSettings or {}) then
        table.insert(parts, "已应用全局调色参数")
    end
    if tableHasEntries(job.localAdjustments or {}) then
        table.insert(parts, job.localGuideMessage or "已打开 Lightroom 局部工具引导")
    end
    if #parts == 0 then
        return "没有需要应用的 Lightroom 参数。"
    end
    return table.concat(parts, "；") .. "。"
end

function applyLocalAdjustmentGuides(localAdjustments)
    switchToDevelopModule()
    local messages = {}
    local createdCount = 0
    local needsUserPlacement = false
    for _, plan in ipairs(localAdjustments) do
        local maskType = tostring(plan.type or "")
        local toolSelected = selectLocalAdjustmentTool(maskType)
        local creation = tryCreateLocalAdjustmentRegion(plan)
        local appliedCount = applyLocalAdjustmentSettings(plan.settings or {})
        if creation.created then
            createdCount = createdCount + 1
        else
            needsUserPlacement = true
        end
        table.insert(messages, localAdjustmentGuideMessage(plan, appliedCount, toolSelected, creation))
    end
    return table.concat(messages, "；"), createdCount, needsUserPlacement
end

function switchToDevelopModule()
    local ok, message = LrTasks.pcall(function()
        LrApplicationView.switchToModule("develop")
    end)
    if not ok then
        error("无法切换到 Lightroom 修改照片模块：" .. tostring(message))
    end
    LrTasks.sleep(0.2)
end

function selectLocalAdjustmentTool(maskType)
    local ok = false
    local toolAttempts = {}

    local function tryTool(label, fn)
        local attemptOk, attemptMessage = LrTasks.pcall(fn)
        if attemptOk then
            ok = true
            return true
        end
        table.insert(toolAttempts, label .. "=" .. tostring(attemptMessage))
        return false
    end

    if maskType == "linear_gradient" and type(LrDevelopController.goToDevelopGraduatedFilter) == "function" then
        tryTool("goToDevelopGraduatedFilter", function()
            LrDevelopController.goToDevelopGraduatedFilter()
        end)
    elseif maskType == "radial_gradient" and type(LrDevelopController.goToDevelopRadialFilter) == "function" then
        tryTool("goToDevelopRadialFilter", function()
            LrDevelopController.goToDevelopRadialFilter()
        end)
    end

    if not ok then
        tryTool("selectTool(masking)", function()
            LrDevelopController.selectTool("masking")
        end)
    end

    if not ok then
        writeDiagnostic("local-adjustment-tool-error.txt", tostring(maskType) .. "=" .. table.concat(toolAttempts, " | "))
    end
    LrTasks.sleep(0.15)
    return ok
end

function tryCreateLocalAdjustmentRegion(plan)
    local maskType = tostring(plan.type or "")
    local region = plan.region or {}
    local feather = numberOrZero(plan.feather)
    local attempts = {}

    if maskType == "linear_gradient" then
        table.insert(attempts, { name = "createLinearGradient", args = { region.x or 0, region.y or 0, region.w or 1, region.h or 0.45, feather } })
        table.insert(attempts, { name = "addLinearGradient", args = { region.x or 0, region.y or 0, region.w or 1, region.h or 0.45, feather } })
        table.insert(attempts, { name = "newGraduatedFilter", args = { region.x or 0, region.y or 0, region.w or 1, region.h or 0.45, feather } })
    elseif maskType == "radial_gradient" then
        table.insert(attempts, { name = "createRadialGradient", args = { region.centerX or 0.5, region.centerY or 0.5, region.radius or 0.3, feather } })
        table.insert(attempts, { name = "addRadialGradient", args = { region.centerX or 0.5, region.centerY or 0.5, region.radius or 0.3, feather } })
        table.insert(attempts, { name = "newRadialFilter", args = { region.centerX or 0.5, region.centerY or 0.5, region.radius or 0.3, feather } })
    else
        return { created = false, method = "manual", message = "当前类型需要在 Lightroom 中手动确认蒙版区域" }
    end

    local errors = {}
    for _, attempt in ipairs(attempts) do
        local fn = LrDevelopController[attempt.name]
        if type(fn) == "function" then
            local ok, result = LrTasks.pcall(function()
                local unpackFn = table.unpack or unpack
                return fn(unpackFn(attempt.args))
            end)
            if ok then
                writeDiagnostic("local-adjustment-create-success.txt", tostring(maskType) .. "=" .. attempt.name)
                return { created = true, method = attempt.name, message = "已尝试自动创建蒙版区域" }
            end
            table.insert(errors, attempt.name .. ":" .. tostring(result))
        else
            table.insert(errors, attempt.name .. ":不可用")
        end
    end

    writeDiagnostic("local-adjustment-create-unavailable.txt", tostring(maskType) .. " " .. table.concat(errors, " | "))
    return { created = false, method = "tool_only", message = "Lightroom SDK 当前未暴露可调用的区域创建函数，已切换工具并写入局部参数，需要在画面中拖拽/确认蒙版范围" }
end

function applyLocalAdjustmentSettings(settings)
    local appliedCount = 0
    for key, value in pairs(settings) do
        local localKey = localAdjustmentParamName(key)
        if localKey ~= nil then
            local ok, errorMessage = LrTasks.pcall(function()
                LrDevelopController.setValue(localKey, value)
            end)
            if ok then
                appliedCount = appliedCount + 1
            else
                writeDiagnostic("local-adjustment-param-error.txt", tostring(localKey) .. "=" .. tostring(errorMessage))
            end
        end
    end
    return appliedCount
end

function localAdjustmentParamName(key)
    local mapping = {
        Exposure2012 = "local_Exposure",
        Exposure = "local_Exposure",
        Contrast2012 = "local_Contrast",
        Contrast = "local_Contrast",
        Highlights2012 = "local_Highlights",
        Highlights = "local_Highlights",
        Shadows2012 = "local_Shadows",
        Shadows = "local_Shadows",
        Whites2012 = "local_Whites",
        Whites = "local_Whites",
        Blacks2012 = "local_Blacks",
        Blacks = "local_Blacks",
        Clarity2012 = "local_Clarity",
        Clarity = "local_Clarity",
        Texture = "local_Texture",
        Dehaze = "local_Dehaze",
        Vibrance = "local_Vibrance",
        Saturation = "local_Saturation",
        Sharpness = "local_Sharpness",
        Temperature = "local_Temperature",
        Tint = "local_Tint",
        LuminanceSmoothing = "local_LuminanceNoise",
        Moire = "local_Moire",
        Defringe = "local_Defringe"
    }
    return mapping[tostring(key)]
end

function localAdjustmentGuideMessage(plan, appliedCount, toolSelected, creation)
    local target = tostring(plan.target or plan.type or "局部区域")
    local maskType = tostring(plan.type or "局部工具")
    local regionText = encodeJson(plan.region or {})
    local base = "“" .. target .. "”" .. localMaskTypeName(maskType) .. "：已设置 " .. tostring(appliedCount) .. " 个局部参数，区域=" .. regionText
    if creation ~= nil and creation.created then
        return base .. "，已通过 " .. tostring(creation.method or "SDK") .. " 尝试自动创建蒙版"
    end
    if maskType == "ai_sky" or maskType == "ai_subject" then
        return base .. "；AI 蒙版需要在 Lightroom 蒙版面板中选择确认"
    end
    if toolSelected then
        return base .. "；已打开对应局部工具，但 Lightroom Classic SDK 未确认支持按坐标自动落点，请在画面中确认蒙版位置"
    end
    return base .. "；局部工具打开失败，请在 Lightroom 中手动创建蒙版"
end

function localMaskTypeName(maskType)
    if maskType == "linear_gradient" then
        return "线性渐变蒙版"
    end
    if maskType == "radial_gradient" then
        return "径向渐变蒙版"
    end
    if maskType == "brush" then
        return "画笔蒙版"
    end
    if maskType == "ai_sky" then
        return "天空 AI 蒙版"
    end
    if maskType == "ai_subject" then
        return "主体 AI 蒙版"
    end
    return "局部蒙版"
end

function tableHasEntries(value)
    if type(value) ~= "table" then
        return false
    end
    for _, _ in pairs(value) do
        return true
    end
    return false
end

function tableSize(value)
    if type(value) ~= "table" then
        return 0
    end
    local count = 0
    for _, _ in pairs(value) do
        count = count + 1
    end
    return count
end

function applyDevelopSettingsJob(job)
    local catalog = LrApplication.activeCatalog()
    local selected = currentTargetPhoto(catalog)
    if selected == nil then
        error("请先在 Lightroom Classic 中选中要修图的照片")
    end

    local developSettings = job.developSettings or {}
    if tableHasEntries(developSettings) then
        catalog:withWriteAccessDo("TonePilot Agent 应用调色参数", function()
            selected:applyDevelopSettings(developSettings)
        end, { timeout = Config.writeAccessTimeoutSeconds or 30 })
    end

    local localAdjustments = job.localAdjustments or {}
    if tableHasEntries(localAdjustments) then
        local guideMessage, createdCount, needsUserPlacement = applyLocalAdjustmentGuides(localAdjustments)
        job.localGuideMessage = guideMessage
        job.localAdjustmentCount = tableSize(localAdjustments)
        job.localMaskCreatedCount = createdCount
        job.localMaskNeedsUserPlacement = needsUserPlacement
    end

    local previewOk, previewUrlOrError = LrTasks.pcall(function()
        local previewFileName = job.previewFileName or (tostring(job.id or "agent-apply") .. ".jpg")
        local previewPath = job.previewPath or LrPathUtils.child(path("results"), previewFileName)
        exportPreview(selected, previewPath)
        return "/files/" .. previewFileName .. "?t=" .. tostring(os.time())
    end)
    if previewOk then
        job.previewUrl = previewUrlOrError
    else
        writeDiagnostic("apply-preview-error.txt", tostring(previewUrlOrError))
    end
end

function currentTargetPhoto(catalog)
    local selected = catalog:getTargetPhoto()
    if selected ~= nil then
        return selected
    end
    local ok, photos = pcall(function()
        return catalog:getTargetPhotos()
    end)
    if ok and photos ~= nil and #photos > 0 then
        return photos[1]
    end
    return nil
end

function writeSelectedPhotoState()
    local catalog = LrApplication.activeCatalog()
    local selected = nil
    local payload = nil

    catalog:withReadAccessDo(function()
        selected = currentTargetPhoto(catalog)
        if selected == nil then
            payload = {
                available = false,
                workerBuild = WORKER_BUILD,
                pluginPath = _PLUGIN.path,
                message = "Lightroom 当前没有选中照片",
                updatedAt = os.time()
            }
            return
        end

        payload = {
            available = true,
            workerBuild = WORKER_BUILD,
            pluginPath = _PLUGIN.path,
            updatedAt = os.time(),
            photo = photoMetadata(selected),
            currentAdjustment = currentAdjustmentFromPhoto(selected)
        }
        payload.metadataDebug = cachedMetadataDiagnostics(selected, payload.photo)
    end)

    if selected ~= nil and payload ~= nil then
        local previewOk, previewUrlOrError = LrTasks.pcall(function()
            return updateSelectedPreview(selected)
        end)
        if previewOk then
            if type(previewUrlOrError) == "table" then
                payload.previewUrl = previewUrlOrError.previewUrl
                payload.baselinePreviewUrl = previewUrlOrError.baselinePreviewUrl
            else
                payload.previewUrl = previewUrlOrError
            end
        else
            payload.previewError = tostring(previewUrlOrError)
            writeDiagnostic("preview-error.txt", tostring(previewUrlOrError))
            if lastPreviewAt ~= 0 and LrFileUtils.exists(LrPathUtils.child(path("results"), "selected-preview.jpg")) then
                payload.previewUrl = "/files/selected-preview.jpg?t=" .. tostring(lastPreviewAt)
                if lastBaselinePreviewUrl ~= "" then
                    payload.baselinePreviewUrl = lastBaselinePreviewUrl
                end
            end
        end
    end

    if payload ~= nil then
        writeFile(path("selected-photo.json"), encodeJson(payload))
        return
    end

    writeFile(path("selected-photo.json"), encodeJson({
        available = false,
        workerBuild = WORKER_BUILD,
        pluginPath = _PLUGIN.path,
        message = "无法读取 Lightroom 当前照片信息",
        updatedAt = os.time()
    }))
end

function updateSelectedPreview(photo)
    local fileName = firstNonEmpty(metadata(photo, "fileName"), metadata(photo, "path"))
    local copyName = metadata(photo, "copyName")
    local photoIdentity = firstNonEmpty(metadata(photo, "path"), fileName) .. "::" .. copyName
    local previewKey = photoIdentity .. "::" .. developPreviewSignature(photo)
    local previewFileName = "selected-preview.jpg"
    local previewPath = LrPathUtils.child(path("results"), previewFileName)

    if previewKey ~= lastPreviewKey or not LrFileUtils.exists(previewPath) then
        exportPreview(photo, previewPath)
        lastPreviewKey = previewKey
        lastPreviewAt = os.time()
    end

    if photoIdentity ~= lastBaselinePhotoKey or lastBaselinePreviewUrl == "" or lastBaselinePreviewPath == "" or not LrFileUtils.exists(lastBaselinePreviewPath) then
        local baselinePreviewFileName = "selected-before-" .. safePreviewName(fileName) .. "-" .. tostring(lastPreviewAt) .. ".jpg"
        local immutableBaselinePreviewPath = LrPathUtils.child(path("results"), baselinePreviewFileName)
        copyBinaryFile(previewPath, immutableBaselinePreviewPath)
        lastBaselinePhotoKey = photoIdentity
        lastBaselinePreviewPath = immutableBaselinePreviewPath
        lastBaselinePreviewUrl = "/files/" .. baselinePreviewFileName .. "?t=" .. tostring(lastPreviewAt)
    end

    return {
        previewUrl = "/files/" .. previewFileName .. "?t=" .. tostring(lastPreviewAt),
        baselinePreviewUrl = lastBaselinePreviewUrl
    }
end

function cachedMetadataDiagnostics(photo, photoSummary)
    local now = os.time()
    local metadataKey = firstNonEmpty(photoSummary.path, photoSummary.fileName) .. "::" .. tostring(photoSummary.copyName or "")
    local interval = Config.metadataDebugIntervalSeconds or 30
    if lastMetadataDebug == nil or metadataKey ~= lastMetadataDebugKey or now - lastMetadataDebugAt >= interval then
        lastMetadataDebug = metadataDiagnostics(photo)
        lastMetadataDebugKey = metadataKey
        lastMetadataDebugAt = now
    end
    return lastMetadataDebug
end

function developPreviewSignature(photo)
    local settings = photo:getDevelopSettings()
    local keys = {
        "Exposure2012",
        "Contrast2012",
        "Highlights2012",
        "Shadows2012",
        "Whites2012",
        "Blacks2012",
        "Temperature",
        "Tint",
        "Texture",
        "Clarity2012",
        "Dehaze",
        "Vibrance",
        "Saturation",
        "HueAdjustmentRed",
        "SaturationAdjustmentRed",
        "LuminanceAdjustmentRed",
        "HueAdjustmentOrange",
        "SaturationAdjustmentOrange",
        "LuminanceAdjustmentOrange",
        "HueAdjustmentYellow",
        "SaturationAdjustmentYellow",
        "LuminanceAdjustmentYellow",
        "HueAdjustmentGreen",
        "SaturationAdjustmentGreen",
        "LuminanceAdjustmentGreen",
        "HueAdjustmentAqua",
        "SaturationAdjustmentAqua",
        "LuminanceAdjustmentAqua",
        "HueAdjustmentBlue",
        "SaturationAdjustmentBlue",
        "LuminanceAdjustmentBlue",
        "HueAdjustmentPurple",
        "SaturationAdjustmentPurple",
        "LuminanceAdjustmentPurple",
        "HueAdjustmentMagenta",
        "SaturationAdjustmentMagenta",
        "LuminanceAdjustmentMagenta",
        "RedPrimaryHue",
        "RedPrimarySaturation",
        "GreenPrimaryHue",
        "GreenPrimarySaturation",
        "BluePrimaryHue",
        "BluePrimarySaturation",
        "GrainAmount",
        "GrainSize",
        "GrainFrequency",
        "PostCropVignetteAmount",
        "PostCropVignetteMidpoint",
        "ParametricShadows",
        "ParametricDarks",
        "ParametricLights",
        "ParametricHighlights",
        "ParametricShadowSplit",
        "ParametricMidtoneSplit",
        "ParametricHighlightSplit",
        "ToneCurveName2012",
        "ToneCurvePV2012",
        "ToneCurvePV2012Red",
        "ToneCurvePV2012Green",
        "ToneCurvePV2012Blue",
        "Sharpness",
        "SharpenRadius",
        "SharpenDetail",
        "SharpenEdgeMasking",
        "LuminanceSmoothing",
        "ColorNoiseReduction",
        "ColorGradeShadowHue",
        "ColorGradeShadowSat",
        "ColorGradeMidtoneHue",
        "ColorGradeMidtoneSat",
        "ColorGradeHighlightHue",
        "ColorGradeHighlightSat",
        "ColorGradeBlending",
        "ColorGradeGlobalHue",
        "ColorGradeGlobalSat",
        "LensProfileEnable",
        "AutoLateralCA",
        "UprightTransformMode",
        "PerspectiveVertical",
        "PerspectiveHorizontal",
        "BluePrimaryHue",
        "BluePrimarySaturation"
    }
    local parts = {}
    for _, key in ipairs(keys) do
        table.insert(parts, key .. "=" .. tostring(settings[key] or ""))
    end
    return table.concat(parts, ";")
end

function metadataDiagnostics(photo)
    local keys = {
        "path",
        "fileName",
        "copyName",
        "fileFormat",
        "camera",
        "cameraMake",
        "cameraModel",
        "lens",
        "lensInfo",
        "isoSpeedRating",
        "iso",
        "focalLength",
        "shutterSpeed",
        "aperture",
        "dateTimeOriginal",
        "captureTime",
        "croppedDimensions",
        "dimensions"
    }
    local result = {}
    for _, key in ipairs(keys) do
        result[key] = metadataProbe(photo, key)
    end
    writeDiagnostic("metadata-debug.json", encodeJson(result))
    return result
end

function metadataProbe(photo, key)
    local formattedOk, formattedValue = LrTasks.pcall(function()
        return photo:getFormattedMetadata(key)
    end)
    local rawOk, rawValue = LrTasks.pcall(function()
        return photo:getRawMetadata(key)
    end)
    return {
        formattedOk = formattedOk,
        formattedValue = formattedOk and metadataValueToString(formattedValue) or "",
        formattedError = formattedOk and "" or tostring(formattedValue),
        rawOk = rawOk,
        rawValue = rawOk and metadataValueToString(rawValue) or "",
        rawError = rawOk and "" or tostring(rawValue)
    }
end

function photoMetadata(photo)
    local camera = firstNonEmpty(
        metadata(photo, "camera"),
        joinNonEmpty({ metadata(photo, "cameraMake"), metadata(photo, "cameraModel") }, " ")
    )

    return {
        path = metadata(photo, "path"),
        fileName = firstNonEmpty(metadata(photo, "fileName"), leafNameFromPath(metadata(photo, "path"))),
        copyName = metadata(photo, "copyName"),
        fileFormat = metadata(photo, "fileFormat"),
        camera = camera,
        lens = firstNonEmpty(metadata(photo, "lens"), metadata(photo, "lensInfo")),
        iso = firstNonEmpty(metadata(photo, "isoSpeedRating"), metadata(photo, "iso")),
        focalLength = metadata(photo, "focalLength"),
        shutterSpeed = metadata(photo, "shutterSpeed"),
        aperture = metadata(photo, "aperture"),
        captureTime = firstNonEmpty(metadata(photo, "dateTimeOriginal"), metadata(photo, "captureTime")),
        dimensions = firstNonEmpty(metadata(photo, "croppedDimensions"), metadata(photo, "dimensions"))
    }
end

function metadata(photo, key)
    local formattedOk, formattedValue = LrTasks.pcall(function()
        return photo:getFormattedMetadata(key)
    end)
    if formattedOk then
        local formatted = metadataValueToString(formattedValue)
        if formatted ~= "" then
            return formatted
        end
    end

    local rawOk, rawValue = LrTasks.pcall(function()
        return photo:getRawMetadata(key)
    end)
    if rawOk then
        return metadataValueToString(rawValue)
    end

    return ""
end

function metadataValueToString(value)
    if value == nil then
        return ""
    end
    if type(value) == "table" then
        return metadataTableToString(value)
    end
    local text = tostring(value)
    if text == "nil" then
        return ""
    end
    return text
end

function metadataTableToString(value)
    local width = value.width or value.w or value[1]
    local height = value.height or value.h or value[2]
    if width ~= nil and height ~= nil then
        return tostring(width) .. " x " .. tostring(height)
    end
    local parts = {}
    for _, item in ipairs(value) do
        local text = metadataValueToString(item)
        if text ~= "" then
            table.insert(parts, text)
        end
    end
    return table.concat(parts, " ")
end

function firstNonEmpty(...)
    for index = 1, select("#", ...) do
        local value = select(index, ...)
        if value ~= nil and tostring(value) ~= "" then
            return tostring(value)
        end
    end
    return ""
end

function joinNonEmpty(values, separator)
    local parts = {}
    for _, value in ipairs(values) do
        if value ~= nil and tostring(value) ~= "" then
            table.insert(parts, tostring(value))
        end
    end
    return table.concat(parts, separator)
end

function leafNameFromPath(filePath)
    if filePath == nil or filePath == "" then
        return ""
    end
    return LrPathUtils.leafName(filePath)
end

function safePreviewName(value)
    local leaf = leafNameFromPath(tostring(value or "photo"))
    if leaf == "" then
        leaf = tostring(value or "photo")
    end
    local base = string.gsub(leaf, "%.[^%.]+$", "")
    base = string.gsub(base, "[^%w%-_]", "-")
    base = string.gsub(base, "%-%-+", "-")
    if base == "" then
        base = "photo"
    end
    return string.sub(base, 1, 80)
end

function currentAdjustmentFromPhoto(photo)
    local settings = photo:getDevelopSettings()
    return {
        style = "Lightroom 当前参数",
        reason = "由 Lightroom 插件后台读取当前选中照片参数",
        basic = {
            exposure = numberOrZero(settings.Exposure2012),
            contrast = intOrZero(settings.Contrast2012),
            highlights = intOrZero(settings.Highlights2012),
            shadows = intOrZero(settings.Shadows2012),
            whites = intOrZero(settings.Whites2012),
            blacks = intOrZero(settings.Blacks2012),
            temperature = intOrZero(settings.Temperature),
            tint = intOrZero(settings.Tint),
            texture = intOrZero(settings.Texture),
            clarity = intOrZero(settings.Clarity2012),
            dehaze = intOrZero(settings.Dehaze),
            vibrance = intOrZero(settings.Vibrance),
            saturation = intOrZero(settings.Saturation)
        },
        hsl = {
            redHue = intOrZero(settings.HueAdjustmentRed),
            redSaturation = intOrZero(settings.SaturationAdjustmentRed),
            redLuminance = intOrZero(settings.LuminanceAdjustmentRed),
            orangeHue = intOrZero(settings.HueAdjustmentOrange),
            orangeSaturation = intOrZero(settings.SaturationAdjustmentOrange),
            orangeLuminance = intOrZero(settings.LuminanceAdjustmentOrange),
            yellowHue = intOrZero(settings.HueAdjustmentYellow),
            yellowSaturation = intOrZero(settings.SaturationAdjustmentYellow),
            yellowLuminance = intOrZero(settings.LuminanceAdjustmentYellow),
            greenHue = intOrZero(settings.HueAdjustmentGreen),
            greenSaturation = intOrZero(settings.SaturationAdjustmentGreen),
            greenLuminance = intOrZero(settings.LuminanceAdjustmentGreen),
            aquaHue = intOrZero(settings.HueAdjustmentAqua),
            aquaSaturation = intOrZero(settings.SaturationAdjustmentAqua),
            aquaLuminance = intOrZero(settings.LuminanceAdjustmentAqua),
            blueHue = intOrZero(settings.HueAdjustmentBlue),
            blueSaturation = intOrZero(settings.SaturationAdjustmentBlue),
            blueLuminance = intOrZero(settings.LuminanceAdjustmentBlue),
            purpleHue = intOrZero(settings.HueAdjustmentPurple),
            purpleSaturation = intOrZero(settings.SaturationAdjustmentPurple),
            purpleLuminance = intOrZero(settings.LuminanceAdjustmentPurple),
            magentaHue = intOrZero(settings.HueAdjustmentMagenta),
            magentaSaturation = intOrZero(settings.SaturationAdjustmentMagenta),
            magentaLuminance = intOrZero(settings.LuminanceAdjustmentMagenta)
        },
        effects = {
            grain = intOrZero(settings.GrainAmount),
            vignette = intOrZero(settings.PostCropVignetteAmount)
        },
        extended = {
            processVersion = metadataValueToString(settings.ProcessVersion),
            treatment = metadataValueToString(settings.Treatment),
            profileName = metadataValueToString(settings.ProfileName),
            parametricShadows = intOrZero(settings.ParametricShadows),
            parametricDarks = intOrZero(settings.ParametricDarks),
            parametricLights = intOrZero(settings.ParametricLights),
            parametricHighlights = intOrZero(settings.ParametricHighlights),
            parametricShadowSplit = intOrZero(settings.ParametricShadowSplit),
            parametricMidtoneSplit = intOrZero(settings.ParametricMidtoneSplit),
            parametricHighlightSplit = intOrZero(settings.ParametricHighlightSplit),
            toneCurveName2012 = metadataValueToString(settings.ToneCurveName2012),
            toneCurvePV2012 = metadataValueToString(settings.ToneCurvePV2012),
            toneCurvePV2012Red = metadataValueToString(settings.ToneCurvePV2012Red),
            toneCurvePV2012Green = metadataValueToString(settings.ToneCurvePV2012Green),
            toneCurvePV2012Blue = metadataValueToString(settings.ToneCurvePV2012Blue),
            sharpness = intOrZero(settings.Sharpness),
            sharpenRadius = numberOrZero(settings.SharpenRadius),
            sharpenDetail = intOrZero(settings.SharpenDetail),
            sharpenEdgeMasking = intOrZero(settings.SharpenEdgeMasking),
            luminanceSmoothing = intOrZero(settings.LuminanceSmoothing),
            luminanceNoiseReductionDetail = intOrZero(settings.LuminanceNoiseReductionDetail),
            luminanceNoiseReductionContrast = intOrZero(settings.LuminanceNoiseReductionContrast),
            colorNoiseReduction = intOrZero(settings.ColorNoiseReduction),
            colorNoiseReductionDetail = intOrZero(settings.ColorNoiseReductionDetail),
            colorNoiseReductionSmoothness = intOrZero(settings.ColorNoiseReductionSmoothness),
            colorGradeShadowHue = intOrZero(settings.ColorGradeShadowHue),
            colorGradeShadowSat = intOrZero(settings.ColorGradeShadowSat),
            colorGradeMidtoneHue = intOrZero(settings.ColorGradeMidtoneHue),
            colorGradeMidtoneSat = intOrZero(settings.ColorGradeMidtoneSat),
            colorGradeHighlightHue = intOrZero(settings.ColorGradeHighlightHue),
            colorGradeHighlightSat = intOrZero(settings.ColorGradeHighlightSat),
            colorGradeBlending = intOrZero(settings.ColorGradeBlending),
            colorGradeGlobalHue = intOrZero(settings.ColorGradeGlobalHue),
            colorGradeGlobalSat = intOrZero(settings.ColorGradeGlobalSat),
            lensProfileEnable = intOrZero(settings.LensProfileEnable),
            lensManualDistortionAmount = intOrZero(settings.LensManualDistortionAmount),
            autoLateralCA = intOrZero(settings.AutoLateralCA),
            defringePurpleAmount = intOrZero(settings.DefringePurpleAmount),
            defringeGreenAmount = intOrZero(settings.DefringeGreenAmount),
            uprightTransformMode = intOrZero(settings.UprightTransformMode),
            perspectiveVertical = intOrZero(settings.PerspectiveVertical),
            perspectiveHorizontal = intOrZero(settings.PerspectiveHorizontal),
            perspectiveRotate = numberOrZero(settings.PerspectiveRotate),
            perspectiveScale = intOrZero(settings.PerspectiveScale),
            perspectiveAspect = intOrZero(settings.PerspectiveAspect),
            perspectiveX = intOrZero(settings.PerspectiveX),
            perspectiveY = intOrZero(settings.PerspectiveY),
            postCropVignetteStyle = intOrZero(settings.PostCropVignetteStyle),
            postCropVignetteMidpoint = intOrZero(settings.PostCropVignetteMidpoint),
            postCropVignetteRoundness = intOrZero(settings.PostCropVignetteRoundness),
            postCropVignetteFeather = intOrZero(settings.PostCropVignetteFeather),
            postCropVignetteHighlightContrast = intOrZero(settings.PostCropVignetteHighlightContrast),
            grainSize = intOrZero(settings.GrainSize),
            grainFrequency = intOrZero(settings.GrainFrequency),
            redPrimaryHue = intOrZero(settings.RedPrimaryHue),
            redPrimarySaturation = intOrZero(settings.RedPrimarySaturation),
            greenPrimaryHue = intOrZero(settings.GreenPrimaryHue),
            greenPrimarySaturation = intOrZero(settings.GreenPrimarySaturation),
            bluePrimaryHue = intOrZero(settings.BluePrimaryHue),
            bluePrimarySaturation = intOrZero(settings.BluePrimarySaturation),
            shadowTint = intOrZero(settings.ShadowTint),
            cropTop = numberOrZero(settings.CropTop),
            cropLeft = numberOrZero(settings.CropLeft),
            cropBottom = numberOrZero(settings.CropBottom),
            cropRight = numberOrZero(settings.CropRight),
            cropAngle = numberOrZero(settings.CropAngle),
            orientation = intOrZero(settings.Orientation)
        }
    }
end

function exportPreview(photo, previewPath)
    local previewDir = LrPathUtils.parent(previewPath)
    createDirectory(previewDir)

    local thumbnailOk, thumbnailError = LrTasks.pcall(function()
        exportPreviewThumbnail(photo, previewPath)
    end)
    if thumbnailOk then
        return
    end

    writeDiagnostic("preview-thumbnail-error.txt", tostring(thumbnailError))
    exportPreviewRendition(photo, previewPath)
end

function exportPreviewThumbnail(photo, previewPath)
    local jpegData = nil
    local startedAt = os.time()
    local retrySeconds = Config.previewRetrySeconds or 20

    while jpegData == nil or jpegData == "" do
        local done = false
        photo:requestJpegThumbnail(1200, 1200, function(jpeg)
            jpegData = jpeg
            done = true
        end)

        while not done do
            if os.time() - startedAt > retrySeconds then
                error("Lightroom 生成预览缩略图超时")
            end
            LrTasks.sleep(0.1)
        end

        if jpegData == nil or jpegData == "" then
            if os.time() - startedAt > retrySeconds then
                error("Lightroom 没有生成预览缩略图")
            end
            LrTasks.sleep(0.5)
        end
    end
    writeBinaryFile(previewPath, jpegData)
end

function exportPreviewRendition(photo, previewPath)
    local previewDir = LrPathUtils.parent(previewPath)
    local tempDir = LrPathUtils.child(previewDir, "tonepilot-export-" .. tostring(os.time()))
    createDirectory(tempDir)

    local exportSession = LrExportSession {
        photosToExport = { photo },
        exportSettings = {
            LR_export_destinationType = "specificFolder",
            LR_export_destinationPathPrefix = tempDir,
            LR_export_useSubfolder = false,
            LR_format = "JPEG",
            LR_jpeg_quality = 0.82,
            LR_size_doConstrain = true,
            LR_size_resizeType = "wh",
            LR_size_maxWidth = 1200,
            LR_size_maxHeight = 1200,
            LR_minimizeEmbeddedMetadata = true,
            LR_outputSharpeningOn = false
        }
    }

    local renderedPath = nil
    for _, rendition in exportSession:renditions { stopIfCanceled = true } do
        local success, pathOrMessage = rendition:waitForRender()
        if success then
            renderedPath = pathOrMessage
        else
            error("Lightroom 导出预览失败：" .. tostring(pathOrMessage))
        end
    end

    if renderedPath == nil or not LrFileUtils.exists(renderedPath) then
        error("Lightroom 导出预览失败：没有生成渲染文件")
    end
    if LrFileUtils.exists(previewPath) then
        LrFileUtils.delete(previewPath)
    end
    local moved = LrFileUtils.move(renderedPath, previewPath)
    if moved == nil and not LrFileUtils.exists(previewPath) then
        error("Lightroom 导出预览失败：无法移动渲染文件")
    end
end

function writeHeartbeat()
    writeFile(path("heartbeat.txt"), tostring(os.time()))
end

function writeDiagnostic(name, content)
    writeFile(path(name), tostring(content))
end

function writeApplyResult(job, success, message)
    local content = "success=" .. tostring(success) .. "\nmessage=" .. sanitizeLine(message) .. "\n"
    if job.previewUrl ~= nil and tostring(job.previewUrl) ~= "" then
        content = content .. "previewUrl=" .. sanitizeLine(job.previewUrl) .. "\n"
    end
    if job.localGuideMessage ~= nil and tostring(job.localGuideMessage) ~= "" then
        content = content .. "localGuideMessage=" .. sanitizeLine(job.localGuideMessage) .. "\n"
    end
    if job.localAdjustmentCount ~= nil then
        content = content .. "localAdjustmentCount=" .. tostring(job.localAdjustmentCount) .. "\n"
    end
    if job.localMaskCreatedCount ~= nil then
        content = content .. "localMaskCreatedCount=" .. tostring(job.localMaskCreatedCount) .. "\n"
    end
    if job.localMaskNeedsUserPlacement ~= nil then
        content = content .. "localMaskNeedsUserPlacement=" .. tostring(job.localMaskNeedsUserPlacement) .. "\n"
    end
    writeFile(job.resultPath, content)
end

function writeApplyResultFromPath(jobPath, success, message)
    local name = LrPathUtils.removeExtension(LrPathUtils.leafName(jobPath))
    local resultPath = LrPathUtils.child(path("apply-results"), name .. ".result")
    local content = "success=" .. tostring(success) .. "\nmessage=" .. sanitizeLine(message) .. "\n"
    writeFile(resultPath, content)
end

function writeFile(filePath, content)
    local file = assert(io.open(filePath, "w"))
    file:write(content)
    file:close()
end

function writeBinaryFile(filePath, content)
    local file = assert(io.open(filePath, "wb"))
    file:write(content)
    file:close()
end

function copyBinaryFile(sourcePath, targetPath)
    local source = assert(io.open(sourcePath, "rb"))
    local content = source:read("*all")
    source:close()
    writeBinaryFile(targetPath, content)
end

function createDirectory(directory)
    if not LrFileUtils.exists(directory) then
        LrFileUtils.createDirectory(directory)
    end
end

function path(child)
    return LrPathUtils.child(Config.bridgeRoot, child)
end

function numberOrZero(value)
    local number = tonumber(value)
    if number == nil then
        return 0
    end
    return number
end

function intOrZero(value)
    return math.floor(numberOrZero(value) + 0.5)
end

function sanitizeLine(value)
    return tostring(value):gsub("[\r\n]", " ")
end

function encodeJson(value)
    local valueType = type(value)
    if valueType == "table" then
        local parts = {}
        local isArray = isArrayTable(value)
        if isArray then
            for index = 1, #value do
                table.insert(parts, encodeJson(value[index]))
            end
            return "[" .. table.concat(parts, ",") .. "]"
        end
        for key, item in pairs(value) do
            table.insert(parts, encodeJson(tostring(key)) .. ":" .. encodeJson(item))
        end
        return "{" .. table.concat(parts, ",") .. "}"
    elseif valueType == "string" then
        return "\"" .. escapeJson(value) .. "\""
    elseif valueType == "number" or valueType == "boolean" then
        return tostring(value)
    end
    return "null"
end

function isArrayTable(value)
    local count = 0
    for key, _ in pairs(value) do
        if type(key) ~= "number" then
            return false
        end
        count = count + 1
    end
    return count == #value
end

function escapeJson(value)
    value = string.gsub(value, "\\", "\\\\")
    value = string.gsub(value, "\"", "\\\"")
    value = string.gsub(value, "\n", "\\n")
    value = string.gsub(value, "\r", "\\r")
    value = string.gsub(value, "\t", "\\t")
    return value
end

return BridgeWorker
