# Manvi Gate App

Android kiosk app for the Manvi Fabricator Gate Receiving page.

- Opens only https://gate.manvihub.com/ in full screen (screen-pinned, back button disabled).
- Adds a Bluetooth printing bridge: when the kiosk calls print, the slip is drawn as a picture and sent
  to the paired 80 mm ESC/POS thermal printer over Bluetooth (no Android print drivers needed).
- Hidden admin menu: tap the top-left corner of the screen 7 times quickly.
  Options: Choose printer, Test print, Set kiosk address, Reload, Exit kiosk.

## Getting the APK
Every push to `main` is built by GitHub Actions. Open the **Actions** tab, open the latest run,
and download **manvi-gate-apk** from the Artifacts section (a zip containing `app-debug.apk`).
