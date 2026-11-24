# What shrinking may do to the release: leave out what nothing uses. Nothing else.
#
# Most of what the libraries bring is never called — the app draws a few hundred icons out of
# several thousand — and every download carried all of it. R8 follows what the code calls and
# leaves the rest out. It can do much more than that, and each of those things is a way for an
# app to work on the build machine and fall over on a phone, so they are switched off here.
# .github/scripts/release_contents.py looks into every release that is built and holds it to
# both halves: what is reached by name only is there, what nothing uses is not.

# No renaming. A crash report names the class it happened in, and whatever is stored or sent
# under a class's name still finds it.
-dontobfuscate

# No rewriting of what stays: the code that runs is the code that was written and tested.
-dontoptimize

# The app's own code stays whole — every class, every member. It is the small part; what is
# left out is in the libraries. So nothing the app reaches by reflection in itself (the
# serializers the compiler generates, enums by name) can go missing.
-keep class com.nchat.app.** { *; }

# Calls. The native library calls back into these classes by name, which no shrinker can see.
-keep class org.webrtc.** { *; }

# What reflection reads off a class that stays.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Annotations that libraries were compiled against and that are not on a phone: named, never
# loaded.
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
