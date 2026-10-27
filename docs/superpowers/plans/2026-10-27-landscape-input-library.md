# Landscape, native prompt handoff and English library

User directly requested these fixes and clarified the desired interaction: tap a node's own text field to open the native prompt editor, bind the actual field and show the IME. Also add an explicit landscape switch and expand built-in English prompts using public reference material. Chinese UI labels and user-authored presets stay intact.

- [x] Add RED→GREEN adapter tests for known widget pointer/focus handoff, exact target identity, ignoring dropdown/search fields and duplicate focus events. Verify against the real frontend DOM.
- [x] Add explicit orientation controls, compact landscape workflow toolbar, stable prompt editor layout and IME focus after window handoff. Do not rebuild views, clear focus, hide the IME, or change tool visibility in response to keyboard resize. Do not poll a hidden WebView during native prompt editing.
- [x] Research and author a categorized bilingual-searchable library with English insertion text. Preserve user presets and translate only exact old shipped defaults; test migration and insertion boundaries.
- [x] Build version 0.2.0 / versionCode 2 using the existing signing key. Run host and JS tests, native compilation/signature checks and real frontend handoff smoke checks. Get independent code review; document actual-device verification limits and deliver an upgrade APK.

Implementation remains in the independent ComfyMobile repository. No ComfyUI backend, model, launcher or firewall changes are needed for this task. A parallel agent owns only the library JSON, its Node data test and source notes; the primary agent owns the native views, adapter, regression tests and packaging.
