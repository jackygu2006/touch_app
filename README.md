# NF Touch (ApkClaw)

An AI-powered Android automation app that lets LLM Agents control Android devices through natural language. Users send commands via messaging channels, and the AI Agent autonomously executes device operations.

## Features

- **Multi-Channel Support**: DingTalk, Feishu, QQ, Discord, Telegram, WeChat
- **AI-Driven**: LangChain4j integration with OpenAI/Anthropic and other LLMs
- **Smart Tool System**: Rich toolset for tap, swipe, text input, screenshot, and more
- **Cloud Control**: WebSocket-based communication with server for remote control
- **Accessibility Service**: Device interaction via Android Accessibility Service
- **Real-Time Screen Streaming**: Live screen preview and remote manipulation
- **Multi-Device Management**: Connect and control multiple devices simultaneously

## System Requirements

- **Android Version**: Android 9.0 (API 28) and above
- **Compile SDK**: Android SDK 36
- **Java Version**: Java 17+
- **Development Tools**: Android Studio (Ladybug or newer)

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────┐
│                    Message Channel Layer                           │
│  DingTalk │ Feishu │ QQ │ Discord │ Telegram │ WeChat             │
└──────────────────────┬─────────────────────────────────────────────┘
                       │ Receive messages
                       ▼
              ┌─────────────────┐
              │  ChannelManager  │  Message routing & dispatch
              └────────┬────────┘
                       │
              ┌────────▼────────┐
              │ TaskOrchestrator │  Task lock & lifecycle
              └────────┬────────┘
                       │
              ┌────────▼────────┐
              │  AgentService    │  Agent loop
              │                  │
              │  ┌────────────┐  │
              │  │  LLM Call  │◄─┼── LangChain4j (OpenAI / Anthropic)
              │  └─────┬──────┘  │
              │        │         │
              │  ┌─────▼──────┐  │
              │  │  Tool Exec │◄─┼── ToolRegistry → ClawAccessibilityService
              │  └─────┬──────┘  │
              │        │         │
              │   Loop until     │
              │   task complete  │
              └────────┬────────┘
                       │
                       ▼
              Reply to user via channel
```

## Core Execution Flow

1. **User sends message**: Sends natural language instruction via configured messaging channel
2. **Channel check**: ChannelSetup verifies accessibility service is running
3. **Task acquisition**: TaskOrchestrator acquires task lock (single-task model), presses Home to reset device state
4. **Agent loop**: DefaultAgentService enters the Agent loop:
   - Builds system prompt with device context (brand, model, resolution, registered tools)
   - Calls LLM (via LangChain4j bridge)
   - Extracts tool calls from LLM response
   - Executes tools via ToolRegistry → ClawAccessibilityService
   - Feeds tool results back to LLM
   - Loops until `finish` tool is called or max iterations (40) reached
5. **Result delivery**: Sends result back to user via the same channel

## Installation & Usage

### Build

Ensure **JDK 17+** is installed and **Android SDK 36** is configured before building.

```bash
# Clone repository
git clone https://github.com/nextflow-matrix/nf-touch.git
cd nf-touch

# Set Android SDK path (adjust to your environment)
export ANDROID_HOME=~/Library/Android/sdk

# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease
```

Build artifacts are located at `app/build/outputs/apk/debug/` or `app/build/outputs/apk/release/`.

### Setup

1. **Install APK**: Install the APK on an Android device (Android 9+)
2. **Grant permissions**: Enable all required permissions on the home screen (Accessibility Service, Notification, System Overlay, Battery Whitelist, File Access)
3. **Configure LLM**: Go to Settings → LLM Config and fill in:
   - **API Key**: OpenAI or Anthropic API key
   - **Base URL**: LLM endpoint (default: `https://api.openai.com/v1`, change for custom providers)
   - **Model Name**: e.g. `gpt-4o`, `claude-sonnet-4-20250514`
4. **Configure channel**: Go to Settings, select at least one messaging channel (DingTalk/Feishu/QQ/Discord/Telegram/WeChat) and fill in Bot credentials
5. **Send message**: Send a message via the configured channel to start controlling the device

### Cloud Control Configuration

1. **Server deployment**: Deploy the companion server application
2. **Device binding**: Generate a pairing code on the server, enter it in the Android app to complete binding
3. **Remote control**: Send `cmd_input`, `cmd_tap`, `cmd_swipe` and other commands via the Dashboard

## Tool System

### Common Tools (all devices)

| Tool | Description |
|------|-------------|
| `get_screen_info` | Get UI hierarchy tree for AI screen analysis |
| `find_node_info` | Find elements by text or resource ID |
| `take_screenshot` | Capture current screen as PNG |
| `input_text` | Input text into focused text field |
| `open_app` | Open app by name |
| `get_installed_apps` | List installed applications |
| `press_back` / `press_home` | Go back / Return to home screen |
| `open_recent_apps` | Open recent apps |
| `expand_notifications` / `collapse_notifications` | Expand / Collapse notification bar |
| `lock_screen` | Lock the screen |
| `wait` | Wait for specified duration |
| `repeat_actions` | Repeat a sequence of actions |
| `send_file` | Send file to user |
| `finish` | Complete task and return summary |

### Mobile-Specific Tools

| Tool | Description |
|------|-------------|
| `tap` | Tap at coordinates (x, y) |
| `long_press` | Long press at coordinates |
| `swipe` | Swipe from point A to point B |
| `click_by_text` | Click element by visible text |
| `click_by_id` | Click element by resource ID |
| `search_app_in_store` | Search for app in app store |

## Tech Stack

### AI / Agent

| Dependency | Version | Purpose |
|------------|---------|---------|
| LangChain4j | 1.12.2 | Agent orchestration, tool definition, LLM integration |

### Messaging Channels

| Dependency | Version | Purpose |
|------------|---------|---------|
| DingTalk Stream Client | 1.3.12 | DingTalk channel |
| Feishu OAPI SDK | 2.5.3 | Feishu channel |

### Networking

| Dependency | Version | Purpose |
|------------|---------|---------|
| OkHttp | 4.12.0 | HTTP client for LLM calls |
| Retrofit | 2.11.0 | REST API client |
| NanoHTTPD | 2.3.1 | LAN config & debug HTTP server |

### Storage & Utilities

| Dependency | Version | Purpose |
|------------|---------|---------|
| MMKV | 2.3.0 | High-performance local key-value storage |
| Gson | 2.13.2 | JSON serialization |
| ZXing | 3.5.3 | QR code generation |
| UtilCode | 1.31.1 | Android utility library |

### UI

| Dependency | Version | Purpose |
|------------|---------|---------|
| Glide | 5.0.5 | Image loading |
| EasyFloat | 2.0.4 | Floating window |
| MultiType | 4.3.0 | RecyclerView multi-type adapter |

## Project Structure

```
app/src/main/java/com/nextflow/nftouch/
├── agent/                  # Agent loop, config, callback
│   ├── langchain/          # LangChain4j bridge & OkHttp adapters
│   └── llm/                # LLM clients (OpenAI, Anthropic)
├── base/                   # BaseActivity (screen density adaptation)
├── channel/                # Message channel handlers
│   ├── dingtalk/
│   ├── feishu/
│   ├── qqbot/
│   ├── discord/
│   ├── telegram/
│   └── wechat/
├── floating/               # Floating button UI manager
├── server/                 # LAN config & debug HTTP server
├── service/                # Accessibility, foreground, keep-alive services
├── tool/                   # Tool abstraction layer & registry
│   └── impl/               # Tool implementations (common/mobile/TV)
├── ui/                     # Activities (splash, home, guide, settings)
├── utils/                  # KVUtils, XLog, formatting utilities
└── widget/                 # Custom UI components
```

## LAN Configuration Server

An HTTP server based on NanoHTTPD runs on port 9527 for convenient configuration from a PC browser:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/` | GET | Configuration web page |
| `/api/channels` | GET/POST | Read / Update channel credentials |
| `/api/llm` | GET/POST | Read / Update LLM configuration |

On GET requests, secrets are masked (only last 4 characters shown). Debug builds additionally expose `/debug.html`, providing a tool execution console.

## Development Guide

### Adding a New Tool

1. Create a tool class extending `BaseTool`
2. Implement `execute(Map<String, Any>): ToolResult`
3. Provide bilingual (English/Chinese) descriptions and typed parameter declarations
4. Register the tool in `ToolRegistry`

### Adding a New Channel

1. Implement the `ChannelHandler` interface
2. Register the channel handler in `ChannelManager`
3. Implement message receiving, parsing, and sending logic

### Debugging Tips

- Use `XLog` for log output
- Use the LAN config server's `/debug.html` for tool execution debugging
- Check Accessibility Service logs for UI interaction troubleshooting

## Known Limitations

- Protected system windows (such as permission dialogs) block node tree access and gesture injection
- Some apps use custom input fields that may require special handling
- WeChat and similar apps may have input fields outside standard FOCUS_INPUT

## License

```
Copyright 2026 NextFlow Matrix

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
