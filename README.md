# Web Apps TV

Android TV app that turns any website into an app. Add a URL once, it gets a tile on the home screen, and opens full-screen like a normal app.

## Using it

**Home screen**
- **Add website** tile → type the URL (e.g. `youtube.com`), optional name, then **Save** (or **Open once** to try it without saving).
- **OK** on a tile = open. **Hold OK** or **MENU** = Edit / Move left / Move right / Delete.
- Options per site: *Desktop mode* (full website instead of mobile version) and *Mouse cursor*.

**Inside a website**
| Remote | Action |
|---|---|
| D-pad | Move the mouse cursor (hold to speed up) |
| OK | Click · hold OK = long-press |
| Push cursor against an edge | Scroll that way |
| CH+ / CH− | Page up / down |
| BACK | Previous page → exits on the first page |
| **Hold BACK** or MENU | Options bar: Back, Forward, Reload, Home, Cursor on/off, Desktop/Mobile site, Zoom, Exit |
| Play/Pause, FF/REW | Play/pause and seek the page's video |

Full-screen video: OK = play/pause, left/right = seek 10 s, BACK = leave full screen.

Logins and cookies are remembered. Pop-ups are blocked.

## Building

Push to `main` → GitHub Actions builds the APK and publishes it to **Releases** (`WebAppsTV.apk`). The signing key (`app/webapps.jks`) is in the repo so every new APK installs over the old one.
