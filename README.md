# Voice Search

An Android voice search app: speak, see the transcript, and the search opens in your browser.
Jetpack Compose + Material 3, single Activity, no backend of its own.

## What it does

- Live transcription while you speak, using the device's own `SpeechRecognizer`
- Auto-searches ~2s after you stop — cancel or edit the text first if you want
- Four engines (Google, YouTube, Bing, DuckDuckGo); the choice is remembered
- Recognition language follows the app's language, or pick one the engine supports
- Falls back to on-device recognition once when the network is unreachable
- Recent searches kept locally (20, de-duplicated), copy/share, in-app updates

## Building

Two files are required but **not** in the repository:

| File | Needed for | Without it |
|---|---|---|
| `key.properties` (repo root) | release signing | release builds are unsigned |
| `app/google-services.json` | Crashlytics | builds fine, no crash reporting |

`key.properties` format:

```properties
storeFile=app/keystore/your-keystore.jks
storePassword=…
keyAlias=…
keyPassword=…
```

Both are git-ignored, and `app/build.gradle` applies the Firebase plugins only when
`google-services.json` is present, so a fresh clone builds without either.

```bash
./gradlew :app:assembleDebug
```

Debug builds use Google's AdMob **test** ad IDs (`app/src/debug/res/values/strings.xml`) and keep
Crashlytics collection off, so development never touches live ads or the crash dashboard.

## Testing

```bash
./gradlew :app:testDebugUnitTest
```

39 unit tests covering URL building and encoding, the preferences/history repository, and the
ViewModel (countdown, the stop-then-late-result race, offline retry, language and engine
persistence). Robolectric is used where an Android API is unavoidable; the repository tests run
against a throwaway DataStore file.

## Architecture

```
MainActivity          splash, dynamic colour, Crashlytics gate, in-app update host
VoiceSearchScreen     all Compose UI, stateless below the top-level composable
VoiceSearchViewModel  RecognitionListener + UI state + one-shot effects
SearchPreferencesRepository  DataStore: history, engine, speech language
SpeechLanguages       asks the recognition service what it supports
AdsConsent            UMP consent gate; ads SDK starts only once consent allows
SearchEngine          enum of search targets, URL-encodes the query
InAppUpdate           Play in-app updates, priority-driven
```

## Privacy

Audio goes to the device's speech recognition service and is never stored or uploaded by this
app. Search history stays on the device and is excluded from cloud backup (see
`app/src/main/res/xml/data_extraction_rules.xml`). See [PRIVACY.md](PRIVACY.md).

## Before a release

- [ ] Publish a GDPR message in the AdMob console (Privacy & messaging). Without one,
      `canRequestAds()` stays false in the EEA and no banner is shown there.
- [ ] Host PRIVACY.md at a public URL and link it in the Play Console.
- [ ] Update Play Data safety: crash logs and device identifiers (Crashlytics), advertising ID
      (AdMob), microphone (not stored).
- [ ] Bump `versionCode` / `versionName` in `app/build.gradle`.
- [ ] Confirm `uploadCrashlyticsMappingFileRelease` ran, or release stack traces stay obfuscated.
