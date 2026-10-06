# Ljós

Will I see the aurora tonight? A native Android app and home-screen widget for Njarðvík and the Reykjanes peninsula.

- **Tonight score (0–100)** from solar activity, cloud cover, moon and darkness, multiplied together so clouds or daylight can zero it on their own.
- **Hour by hour** strip from dusk to dawn. Tap or drag to see why any hour scores what it does.
- **Where to go**: compares cloud cover at Garðskagi, Hafnir, Reykjanesviti and Kleifarvatn against home.
- **Alerts**: an evening heads-up when tonight reaches your level, and a live "look up now" when the solar wind turns south while it's dark and clear.
- **Widgets**: a 2×2 score tile and a 4×2 with tonight's bars.

No server. The phone pulls the feeds itself about every 15 minutes.

## Data

| Feed | Used for |
| --- | --- |
| NOAA SWPC planetary Kp forecast | Activity by 3-hour block |
| NOAA SWPC solar wind summaries (Bz, speed) | Live boost for the current hour, "look up now" |
| NOAA SWPC real-time solar wind (1-minute) | Fallback when the summary lags, only at night |
| Open-Meteo hourly cloud layers | Clear-sky factor per spot |
| On-device sun and moon maths | Darkness and moonlight |

## Install

Every push to `main` builds a signed APK and publishes it as a GitHub Release. Open the latest release on your phone and tap `ljos.apk`. Builds are signed with one key kept in the repo's Actions secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`), never in the code, so new versions install over the old one.

## Layout

```
app/src/main/java/app/ljos/
  data/     feeds, parsers, on-disk cache
  model/    sun/moon maths and the scoring model
  ui/       Compose screen, GPU aurora shader, hour strip
  widget/   Glance widgets and their bitmap art
  work/     15-minute refresh worker and notifications
```
