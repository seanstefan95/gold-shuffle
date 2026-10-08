# Gold Shuffle

**A (vibecoded) true-random shuffle for Spotify.** Spotify's shuffle favours some songs, so the same
few keep coming up. Gold Shuffle numbers every song in a playlist and orders them with a
Fisher–Yates shuffle, so every order is equally likely and every song gets its turn.

- **Android app.** Gold-shuffle any playlist, album or Liked Songs, including playlists
  you only follow. It reshuffles offline from saved lists while it's connected to Spotify,
  and it's polite to your queue: only one Gold Shuffle song waits there at a time,
  its leftovers are skipped, and songs you queue yourself are never touched.
- **PC (Spotify desktop).** Adds a gold mode to Spotify's own shuffle button:
  click to cycle **gold → shuffle → smart shuffle → off**.

Requires **Spotify Premium**.

---

## Android

### 1. Install
Download `GoldShuffle-<build>.apk` from the latest release (or the file a friend sent you),
open it on your phone and allow the install when Android asks.

### 2. Make your own Spotify developer app (one time, ~2 minutes)
Spotify only lets an app control playback if it's registered, and each registration is
limited to a handful of people, so everyone uses their own free one. The app walks you
through this and has copy buttons for every value:

1. Go to <https://developer.spotify.com/dashboard>, log in with your normal Spotify
   account, accept the terms and click **Create app** (name and description can be anything).
2. **Redirect URIs**: add both `goldshuffle://callback` and `goldshuffle://sdkauth`.
3. **APIs**: tick **Web API** and **Android**. Under **Android packages** enter the
   package name `com.goldshuffle` and the SHA1 fingerprint shown in the app, press the
   purple **Add** button, then **Save** at the bottom.
4. Open your app's **Settings**, copy the **Client ID** (not the secret) and paste it into Gold Shuffle.

### 3. Sign in
Tap **Sign in with Spotify**, then approve the second Spotify prompt that connects
Gold Shuffle to the Spotify app.

### Using it
- **★ Gold shuffle what's playing**: reshuffles the current playlist; the song playing keeps playing.
- **Tap a playlist**: starts it in a gold order. **⤓** means it's saved for offline.
- **Home-screen shortcut**: long-press the icon → *Gold shuffle now*.
- **Offline-ready** (on by default) keeps a connection to Spotify open so gold shuffle
  still works if you lose signal. The connection has to be made while online.

---

## PC (Spotify desktop, via Spicetify)

1. Install [Spicetify](https://spicetify.app/docs/getting-started) (the Marketplace is not needed).
2. Run `spicetify config-dir` and put `goldShuffle.js` (from the latest release, or `pc/`
   in this repo) into the `Extensions` folder it shows.
3. Run:
   ```
   spicetify config extensions goldShuffle.js
   spicetify apply
   ```
4. Spotify restarts and shows *Gold Shuffle ready*. Click the shuffle button: it turns gold.

When a Spotify update removes the mod, run `spicetify apply` again.

---

## Troubleshooting
| Problem | Fix |
|---|---|
| `AUTHENTICATION_SERVICE_UNAVAILABLE` | The Android package isn't saved on your dashboard. Check step 3 (press **Add**, then **Save**), then sign out and back in. |
| A followed playlist won't load | Make sure the Spotify-app approval was accepted; followed playlists are read through the Spotify app. |
| Nothing happens offline | Open Gold Shuffle once while online with **Offline-ready** on, before losing signal. |
| PC shuffle button isn't gold | Run `spicetify apply` and look for *Gold Shuffle ready* when Spotify starts. |

## Notes
Not affiliated with Spotify. The PC version modifies the Spotify desktop client, which
Spotify's terms don't allow; use at your own risk. The Android app only uses Spotify's
official Web API and Android SDK.

Includes the Spotify App Remote SDK (Apache License 2.0). See `NOTICE`.
