# LadaStylePlayer

Android music player prototype for head units (Teyes CC3), focused on USB playback via SAF.

## Build
1. Open project in Android Studio Iguana+.
2. Sync Gradle.
3. Regenerate the missing wrapper JAR locally: `./scripts/regenerate-wrapper.sh`.
4. Build with `./gradlew assembleDebug`.

## Run on device
1. Install debug APK from `app/build/outputs/apk/debug/`.
2. Launch app in landscape mode.
3. Press **Browse** and choose USB root folder.
4. Use **Play All** or open tracks directly.

## Current scope
- SAF folder selection with persistable permissions.
- Folder browsing in two RecyclerView columns.
- Foreground playback service with Media3 ExoPlayer.
- Notification controls (Prev/PlayPause/Next).
- Track title and optional embedded cover broadcast to UI.
- Basic state persistence for mute/index/position.


## Repository policy
- Binary files are intentionally excluded (including `gradle/wrapper/gradle-wrapper.jar`) to satisfy repository limitations.
- After clone, run `./scripts/regenerate-wrapper.sh` to regenerate the wrapper JAR locally.

## Troubleshooting Gradle sync
- If Android Studio shows a long Gradle Tooling stack trace during sync, first regenerate the wrapper JAR with `./scripts/regenerate-wrapper.sh`.
- If you see `Unsupported class file major version 69`, switch Gradle JDK to Java 17 (File → Settings → Build Tools → Gradle → Gradle JDK).
