# R8 keeps everything Compose needs through its own consumer rules; the only app-specific
# surface here is org.json, which ships with the platform but is also compiled into the APK
# for unit tests. Keep its members so Model.kt/Rules.kt parsing cannot be renamed away.
-keep class org.json.** { *; }
-dontwarn org.json.**
