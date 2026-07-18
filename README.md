# Touch App

Touch App is the Android runtime of Touch Matrix. It turns a real Android phone into an addressable execution node that can be operated through Android Accessibility APIs, an on-device LLM agent loop, and the companion cloud control server.

The current codebase is centered around the mobile runtime itself:

- An Accessibility-based execution layer for gestures, key events, node lookup, screenshots, and text input
- A LangChain4j-based agent loop that can run on-device tasks when an LLM is configured
- A cloud control channel built on `WebSocket` for pairing, live screen streaming, remote commands, and dashboard feedback
- A local LAN configuration server on port `9527` for browser-based configuration and debug tooling

## What It Does

- Controls a real Android device through `ClawAccessibilityService`
- Streams the live screen to the dashboard through `CloudHub` and `ScreenStreamer`
- Executes remote commands such as tap, swipe, key press, text input, and AI task dispatch
- Keeps the runtime alive through a foreground service, battery optimization handling, and a scheduled keep-alive job
- Shows execution status through a floating overlay
- Applies privacy-aware screen tree reduction for finance-sensitive apps

## Current Scope

The actual code currently boots with `ToolRegistry.DeviceType.MOBILE` and registers only the `Cloud` channel in `ChannelManager`.

That means the real, implemented control paths today are:

1. **Cloud control**: Pair the phone with the Go server in `../server`, then control it from the web dashboard
2. **Local browser config**: Open the built-in LAN config page from a PC browser on the same network
3. **On-device AI task execution**: Available after LLM configuration, and currently triggered through the cloud command path

## Architecture

```text
Dashboard / Browser
        |
        |  /api/pairing-code, /api/device/validate-token, /ws/dash
        v
Go server in ../server
        |
        |  /ws/device
        v
CloudHub -----------------------> ScreenStreamer
   |                                   |
   | remote commands                   | JPEG frames
   v                                   v
RemoteExecutor                    Live preview in dashboard
   |
   | cmd_tap / cmd_swipe / cmd_input / cmd_key / cmd_task
   v
ClawAccessibilityService
   |
   +--> gesture injection
   +--> node tree inspection
   +--> screenshots
   +--> system key operations
   |
   v
TaskOrchestrator -> AgentService -> ToolRegistry -> mobile tools
```

## Main Modules

### Runtime And App Lifecycle

- `ClawApplication`: app bootstrap, tool registration, foreground service startup, async initialization
- `AppViewModel`: central coordinator for permissions, agent init, floating UI, config server, and task orchestration
- `TaskOrchestrator`: single-task lock, agent lifecycle, task callbacks, cancellation, and result delivery

### Device Control

- `service/ClawAccessibilityService.java`: core Accessibility service for gestures, node lookup, clicks, screenshots, and key operations
- `device/RemoteExecutor.kt`: translates dashboard commands into device actions and starts AI tasks
- `device/DeviceInfo.kt`: collects device metadata used for auth and status reporting
- `floating/FloatingCircleManager.kt`: floating indicator for runtime state

### Cloud Control

- `device/CloudHub.kt`: `WebSocket` client for auth, reconnect, heartbeat, RTT ping, status sync, and command handling
- `device/ScreenStreamer.kt`: live screen streaming with adaptive width tiers
- `device/RemoteExecutor.kt`: remote command bridge between the dashboard and Accessibility execution

### Local Configuration

- `server/ConfigServer.kt`: embedded HTTP server on port `9527`
- `server/ConfigServerManager.kt`: lifecycle manager for the local config server
- `app/src/main/assets/web/index.html`: browser configuration page
- `app/src/main/assets/web/debug.html`: debug-only tool execution page

### UI

- `ui/splash/SplashActivity.kt`: launcher entry
- `ui/home/HomeActivity.kt`: permission dashboard, LLM entry, cloud bind entry, runtime status
- `ui/guide/GuideActivity.kt`: first-run permission guide
- `ui/settings/LlmConfigActivity.kt`: LLM configuration
- `ui/settings/SettingsActivity.kt`: LAN config, cloud bind, and model entry

## Features Backed By Code

### Accessibility Runtime

- Tap, long press, swipe, and center-point fallback click
- Find nodes by text or view ID
- Inspect the full accessibility tree
- Take screenshots and send files back through the active task channel
- Trigger system keys such as home, back, recent apps, and lock screen

### AI Task Execution

- LLM config stored locally through `KVUtils`
- OpenAI-compatible and Anthropic-compatible clients through LangChain4j
- Agent execution loop with tool calls, round callbacks, token statistics, and cancellation
- Task execution gated by a single-task lock in `TaskOrchestrator`

### Cloud Dashboard Integration

- Device auth through `/ws/device`
- Pairing and token validation through HTTP calls to the companion server
- Dashboard-to-device commands:
  - `cmd_tap`
  - `cmd_swipe`
  - `cmd_input`
  - `cmd_task`
  - `cmd_key`
  - `cmd_cancel_task`
- Immediate and heartbeat-based device status reporting
- Adaptive screen streaming based on RTT and frame-send congestion

### Local Browser Config

- `GET /` or `GET /index.html`: configuration page
- `GET/POST /api/llm`: read and update LLM config
- `GET/POST /api/channels`: placeholder channel config endpoint
- `GET /debug.html`: debug page, debug builds only
- `GET /api/debug/tools`: list runtime tools, debug builds only
- `POST /api/debug/execute`: execute a tool from browser, debug builds only

## Runtime Tool Surface

The app initializes in mobile mode, so the current runtime tool set is:

### Common Tools

- `get_screen_info`
- `find_node_info`
- `input_text`
- `system_key`
- `open_app`
- `get_installed_apps`
- `take_screenshot`
- `wait`
- `repeat_actions`
- `clipboard`
- `send_file`
- `finish`

### Mobile Tools

- `tap`
- `long_press`
- `swipe`
- `scroll_to_find`

## Privacy Behavior

The Accessibility layer contains explicit privacy handling for sensitive apps.

- A blacklist includes apps such as WeChat, Alipay, Bank of China, ICBC, QQ, Google Play, Amap, WeCom, Pinduoduo, and JD
- In those contexts, the screen tree can switch to a reduced mode to avoid exposing full text content
- First launch shows a privacy notice explaining that UI element metadata may be sent to the AI service, while password, verification code, and banking text content are not sent as normal text payloads

## Requirements

- Android `9.0+` (`minSdk = 28`)
- Target / compile SDK `36`
- Java `17`
- Android Gradle Plugin `9.1.0`
- Gradle wrapper included in the repository

## Build

### Debug Build

```bash
cd touch_app
export ANDROID_HOME=~/Library/Android/sdk
./gradlew assembleDebug
```

### Release Build

```bash
cd touch_app
./gradlew assembleRelease
```

Generated APK files are renamed during the build to:

```text
Touch App_v<versionName>_<timestamp>.apk
```

The version is currently maintained in `app/build.gradle.kts`, and `buildConfigField("VERSION_INFO", ...)` also embeds the current Git branch and commit SHA.

## One-Click Build And Install

The repository includes `build_and_install.sh`, which:

1. Reads and bumps the version in `VERSION`
2. Updates `versionCode` and `versionName` in `app/build.gradle.kts`
3. Builds the debug APK
4. Installs it to the connected device through `adb`

Usage:

```bash
cd touch_app
./build_and_install.sh
./build_and_install.sh <device_serial>
```

## First-Run Setup

After installing the APK, the real setup flow in the app is:

1. Open the app and complete the permission guide
2. Enable Accessibility Service
3. Enable persistent notification
4. Enable overlay permission
5. Exempt the app from battery optimization
6. Grant storage access
7. Configure an LLM if you want to run `cmd_task` or other AI-driven flows
8. Bind to the cloud server if you want dashboard-based remote control

## LLM Configuration

The LLM settings page stores:

- `API Key`
- `Base URL`
- `Model Name`
- Provider selection metadata

If `Base URL` is empty, the runtime falls back to:

```text
https://api.openai.com/v1
```

The current agent config in `AppViewModel` uses:

- `temperature = 0.1`
- `maxIterations = 100`

## Cloud Control Setup

Touch App is designed to work with the Go companion server in `../server`.

The in-app cloud bind flow is:

1. Enter the server address such as `ws://<host>:8443` or `wss://<host>:8443`
2. Enter a device ID
3. Request a pairing code from `POST /api/pairing-code`
4. Complete pairing in the dashboard
5. Paste the issued token back into the app
6. Validate the token
7. Let `CloudHub` connect to `GET /ws/device`

Once connected, the app will:

- Authenticate with device metadata
- Stream the screen
- Report battery, screen state, and permission state
- Accept remote dashboard commands

## LAN Config Server

Touch App also exposes a local configuration server built on NanoHTTPD.

- Port: `9527`
- Entry page: `http://<phone-ip>:9527/`
- LLM config API: `/api/llm`
- Debug UI: `/debug.html` in debug builds only

This is mainly intended for quick browser-side configuration and tool debugging on the same network.

## Project Structure

```text
touch_app/
├── app/
│   ├── src/main/java/com/nextflow/nftouch/
│   │   ├── agent/          # Agent loop and LLM clients
│   │   ├── base/           # Base app / activity classes
│   │   ├── channel/        # Current channel abstraction, currently Cloud
│   │   ├── device/         # Cloud hub, remote executor, screen streaming, device info
│   │   ├── floating/       # Floating runtime indicator
│   │   ├── server/         # Embedded LAN config server
│   │   ├── service/        # Accessibility, foreground, boot, keep-alive services
│   │   ├── tool/           # Tool abstraction and implementations
│   │   ├── ui/             # Splash, home, guide, settings, web UI
│   │   ├── utils/          # KV, logging, helpers
│   │   ├── widget/         # Reusable custom views
│   │   ├── AppViewModel.kt
│   │   ├── ClawApplication.kt
│   │   └── TaskOrchestrator.kt
│   ├── src/main/assets/web/
│   │   ├── index.html      # LAN config page
│   │   └── debug.html      # Debug tool page
│   └── src/main/AndroidManifest.xml
├── gradle/
├── build_and_install.sh
├── VERSION
└── README.md
```

## Key Dependencies

- `dev.langchain4j:langchain4j-core`
- `dev.langchain4j:langchain4j-open-ai`
- `dev.langchain4j:langchain4j-anthropic`
- `com.squareup.okhttp3:okhttp`
- `com.squareup.retrofit2:retrofit`
- `com.tencent:mmkv-static`
- `org.nanohttpd:nanohttpd`
- `com.github.princekin-f:EasyFloat`
- `com.github.bumptech.glide:glide`
- `com.google.zxing:core`

## Notes And Limitations

- Accessibility permission is mandatory for any real device control
- `cmd_task` requires a valid LLM configuration
- Text input has multiple fallbacks, but the shell-based fallback only supports ASCII text
- The current repository contains channel-related UI wording, but the implemented runtime message channel is currently `Cloud`
- Sensitive apps may expose a reduced accessibility tree instead of full text content

## Companion Server

Touch App depends on the Go backend in `../server` for:

- pairing code generation
- token validation
- device unbind
- dashboard sessions
- cloud device sessions
- browser-based remote control

If you are deploying the full system, read `../server/README.md` together with this file.

## License

See `LICENSE`.
