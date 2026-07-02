return {
    LrSdkVersion = 12.0,
    LrToolkitIdentifier = "com.tonepilot.lightroom.bridge",
    LrPluginName = "TonePilot Lightroom Bridge",
    LrPluginInfoUrl = "http://localhost:33335/status",
    LrInitPlugin = "Init.lua",
    LrShutdownPlugin = "Shutdown.lua",
    LrExportMenuItems = {
        {
            title = "打开 TonePilot Agent 控制台",
            file = "AgentConsole.lua"
        }
    },
    VERSION = {
        major = 0,
        minor = 1,
        revision = 0,
        build = 24
    }
}
