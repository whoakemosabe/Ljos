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
core/src/main/kotlin/app/ljos/     plain Kotlin, no Android: shared by the app and the server
  data/     feed parsers, data classes, comparison spots
  model/    sun/moon maths, live solar wind and the scoring model

server/src/main/
  kotlin/   routes, feed cache, forecast JSON
  resources/web/   the web app: phone and TV layouts, WebGL aurora

app/src/main/java/app/ljos/
  data/     fetching, location, on-disk cache, map tiles, updater
  model/    the app's wording for score bands
  ui/       Compose screen, GPU aurora shader, hour strip
  widget/   Glance widgets and their bitmap art
  work/     15-minute refresh worker and notifications
```

## Web app and lobby screen

`server/` is a small Kotlin server (JDK built-in HTTP server, no framework) that scores the sky with the same `core/` code and serves a web app with two layouts:

- **Phone** (`/`): works in Safari and Chrome, installable to the home screen, shares location to score where you are.
- **TV** (`/lobby/{place}`): one full screen for a hotel lobby, refreshed every minute, with a full-screen "Look up now" when it's happening. Places are listed in `server/src/main/resources/places.json`.

| Route | What |
| --- | --- |
| `GET /v1/forecast?lat=&lon=&tz=` | Forecast for any position, cached per ~10 km cell |
| `GET /v1/places/{id}` | Forecast for a saved place |
| `GET /healthz` | Feed status |

Feeds are fetched only when someone asks and the copy is stale, so an idle server makes no requests. Run it locally with `./gradlew :server:run` (port 8080). Railway builds it from the `Dockerfile` (see `railway.toml`).

`core/` returns numbers and keys (for example `Chance.GOOD`), never display text, so each screen words things its own way. Its tests run with `./gradlew :core:test`.
