# ComfyMobile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an installable Android LAN companion to ComfyUI with the full existing editor, mobile prompt input, task monitoring and original-image saving.

**Architecture:** A native Java Activity hosts the user's existing ComfyUI frontend in a persistent system WebView. A small JavaScript adapter reads/writes actual graph widgets, exposes touch editing actions and forwards progress; the app invokes it through evaluateJavascript without exposing a native JavaScript bridge. Native views provide connection, prompt editing and history/gallery, with HTTP requests restricted to the configured origin.

**Tech Stack:** Java, Android SDK 35 / minSdk 29, system WebView, JavaScript; Node's built-in test runner. Official JDK and Android build tools; a dependency-free PowerShell APK build.

**Spec:** ../specs/2026-10-27-comfy-mobile-design.md (approved by user: “ok开始做吧”).

## Global Constraints

- Independent R:/ComfyUI-aki-v3.2/ComfyMobile project; no ComfyUI core/model modifications.
- Android 10+, same Wi-Fi, no cloud account, public relay or public port mapping.
- Prompt field identity is graph session + node + widget, never guessed positive/negative.
- Chinese IME composition/Enter must not submit generation; save and generate are separate.
- Original-image save uses MediaStore; import uses a system document picker.
- A compiled APK is not evidence of a successful physical-device test; state test limitations.

## Review Focus

- Workflow replacement during prompt editing must reject stale saves and retain a draft.
- Graph field deletion/replacement must not update an unrelated widget with the same index.
- Redirects, external URLs and malformed filenames must not access unrelated origins or paths.
- Loss of connection while generation is submitted must never automatically retry submission.
- Keyboard, system bars, rotation and history refresh must preserve text and usable controls.

### Task 1: Connection policy and reproducible build

**Files:** scripts/build.ps1, scripts/setup-tools.ps1, app/src/main/AndroidManifest.xml, app/src/main/res/values/styles.xml, app/src/main/java/local/comfymobile/ServerAddress.java, tests/ServerAddressTest.java.

**Interfaces:** ServerAddress.parse(String) returns a normalized root HTTP(S) origin; resolve(String) accepts origin-relative endpoint paths, rejects foreign origins/user info/query/fragment in configured addresses. build.ps1 produces output/ComfyMobile-0.1.0.apk and verifies its signature.

- [ ] Write native host tests for LAN IP/default scheme/trailing slash/IPv6 and rejection of credentials, paths and foreign origins. Run javac/test and observe missing class failure.
- [ ] Implement ServerAddress, build configuration and SDK setup using official archives/checksums. Run native host tests to PASS.
- [ ] Build Android resources with aapt2, compile Java, dex, zipalign and sign a development APK after Activity exists in Task 3. Keep build tools and signing key git-ignored; preserve the key for upgrades.

### Task 2: Real workflow field and touch adapter

**Files:** app/src/main/assets/editor-adapter.js, tests/editor-adapter.test.cjs.

**Interfaces:** window.ComfyMobileEditor exposes snapshot(), fields(), readField(target), writeField(target,text), command(name,args), drainEvents(). Every method returns {ok,...} or {ok:false,error}. Targets contain session, nodeId, widgetName and widgetIndex. Fields support standard string widgets, including custom-node types. commands include nodes, select, add, remove, duplicate, connect, disconnect, undo, fit, export, import and generate.

- [ ] Write Node tests exercising field read/write, callback propagation, replaced graph, reused ids after configure, deleted widget, non-string widgets, import/export and no duplicate generation. Observe failure before adapter exists.
- [ ] Implement runtime adapter against window.comfyAPI.app.app and current graph/widgets, with optional dynamic LiteGraph import. No frontend package patches.
- [ ] Test generation single-flight and safe import parse-before-apply, explicit unsupported-state errors, event queue bounds; run node --test tests/*.test.cjs to PASS.

### Task 3: Native Android workflow and prompt editing

**Files:** MainActivity.java, EditorView.java, PromptEditor.java, Ui.java, PromptText.java, tests/PromptTextTest.java; Android resources/assets.

**Interfaces:** EditorView.call(method,args,Consumer<JSONObject>) invokes only the configured origin; PromptEditor binds immutable field targets, owns drafts/undo/history/templates/favorites; MainActivity owns selected server, page navigation and lifecycle. PromptText.weight(text,start,end,delta) returns updated text and selection with a default weight of 1.0.

- [ ] Add host tests for selection weights, existing weighted phrases, Unicode, invalid selection and no global rewriting; observe RED then implement to PASS.
- [ ] Implement dark teal native UI, connection test, retained WebView, four tabs and large safe-area controls. Add native node picker/actions so touch users have alternatives to desktop menus.
- [ ] Implement full-screen multiline IME-safe native prompt editor: field picker, explicit save-to-node, draft persistence, history/templates/favorites, cursor insertion, weights, undo/redo, separate generate action. Preserve editor during activity recreation; use adjustResize and system insets.
- [ ] Implement file chooser, workflow JSON import/export, external-navigation handling and confirm leaving unsaved drafts. Compile APK as native integration verification.

### Task 4: Tasks, results, connection helper and delivery

**Files:** ComfyClient.java, ResultsView.java, scripts/start-comfy-lan.ps1, README.md, docs/verification.md.

**Interfaces:** ComfyClient uses bounded HTTP requests and same-origin redirect policy; history/queue parsed by ResultsView with thumbnail/preview and original-image actions. MainActivity fetches task history only while active; WebView adapter captures live API progress and binary preview events where supported. No background resubmit.

- [ ] Add tests for history extraction with multi-output/non-ASCII filenames and build image URLs by encoding query components; observe RED then implement to PASS.
- [ ] Implement queue/history refresh, progress feedback, original-image save/share with bounded background work and MIME checks. Clean up failed MediaStore entries.
- [ ] Add a LAN startup helper that detects an existing ComfyUI process and refuses a second instance. Preserve user launcher parameters by documenting launcher configuration as preferred; do not auto-edit firewall.
- [ ] Run host tests, APK build, apksigner verify, aapt package inspection and JS syntax validation. Document all actual outcomes, untested device/plugin interactions and APK SHA256.
- [ ] Dispatch one fresh-context whole-project reviewer, fix material findings with regression tests, rerun affected checks, package source and deliver APK + instructions.

## Execution record

User explicitly asked to start after approving the design and reference image. Execute inline without another approval round. The new independent repository is the isolated workspace; no ComfyUI worktree or dependency installation is needed. The PowerShell build avoids Gradle/Maven downloads while using the standard official Android compiler, resource linker, dexer and signer. UI verification is compile/static only until a device or emulator is available.
