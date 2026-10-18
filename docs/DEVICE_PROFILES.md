# Device adaptation

Run `scripts/inspect_device.sh` with one authorized device connected. The local,
ignored `config/device-profile.json` records only non-secret compatibility data.

## Supported baseline

- Android 9 / API 28 or newer
- arm64-v8a and emulator ABIs supported by the Android toolchain
- Phones, tablets, and foldables using responsive Compose layouts
- Light theme today; vendor-specific dark-mode forcing is not a supported mode

## What an agent should inspect

1. Android API and manufacturer battery restrictions.
2. Effective window size, not physical model name.
3. Display density and font scale.
4. Status/navigation bars, cutouts, hinges, and IME insets.
5. Adaptive-icon mask on the actual launcher.
6. Whether background networking survives screen-off and vendor power saving.

## Adaptation rules

- Use `WindowInsets` and `adjustResize` for the input bar.
- Use adaptive icons and keep important art inside the central safe zone.
- Use capability/API checks instead of model or serial-number branches. A
  manufacturer check is allowed only as part of a documented vendor-only API
  gate, such as Xiaomi HyperOS 4 Super Island, and must be combined with the
  vendor's runtime capability and permission checks.
- Treat keyboard, fold state, and orientation as runtime state.
- Keep touch targets at least 48 dp.
- Preserve scroll position; opening a chat should land at its newest message
  without visibly animating through the entire history.
- Keep chat Markdown inside a selection container so long-press selection,
  drag handles, and the native copy menu remain available on every device.

## Validation matrix

At minimum, test one phone on API 28-32 and one modern phone/emulator on API
33+. For a foldable/tablet change, test compact and expanded widths. For every
device verify: first pairing, reconnect, screen-off recovery, chat switching,
Markdown, HTML full-screen mode, local-file preview, and launcher icon mask.
