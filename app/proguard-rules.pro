# Ljós R8 rules.
#
# Nothing here yet on purpose: the app uses no reflection or JSON-to-class mapping, and every
# library it uses ships consumer keep rules (WorkManager keeps RefreshWorker; activities,
# receivers and the FileProvider are kept because they're in the manifest).
#
# If a release build crashes where the debug build doesn't, add a targeted -keep for that class
# here rather than a package-wide rule.

# Readable stack traces from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
