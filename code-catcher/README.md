# Code Catcher v2.2

Accessibility service that captures a `XXXX-XXXX-XXXX` code from the screen.

- Trigger: **Volume+** or **key 353** (keyCode or scanCode 353).
- Strips the dashes and copies the 12 digits to the clipboard.
- **Auto Paste ON**: then taps 配達 → 手入力 → focuses the 伝票番号を入力 field,
  pastes the code + ENTER, waits 1 second and, if the screen was not touched, taps 配完.

`src/` holds the rewritten `MainActivity` and `CodeCatcherService`; they replace the
classes in the original `Code-Catcher-v2.apk` (decompiled with apktool, rebuilt,
zipaligned and signed with a new key, so uninstall the old version first).
