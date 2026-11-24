#!/usr/bin/env bash
#
# Runs plain-JVM unit tests of the app without Gradle or the Android SDK —
# minutes of CI become seconds on any machine that has Python's pip.
#
#   tools/jvm-tests.sh                 compile and run every test listed below
#   tools/jvm-tests.sh SomeTest ...    run only the named test classes
#
# It is a development aid, not the gate: CI (`:app:testDebugUnitTest`) builds
# with the project's own Kotlin version against android.jar, and the emulator
# workflow runs the encryption tests on Android itself. This script uses
# kotlinc 1.9.10 on a desktop JRE 17 and compiles only the files listed here.
#
# Two groups:
#   MAIN / ALL_TESTS      the encryption core, which must stay free of
#                         `android.*` imports altogether.
#   APP_MAIN / APP_TESTS  app code around it — the models and the on-phone
#                         stores. It is free of Android but for three names
#                         (a Context that is only asked for its files
#                         directory, the crash log, Compose's @Immutable);
#                         stand-ins for those are generated below and exist
#                         nowhere but in this script's work directory.
#
# First run downloads a JRE and a Kotlin compiler from PyPI (about 120 MB)
# into $NCHAT_JVM_TOOLS (default ~/.local/nchat-jvm-tools).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${NCHAT_JVM_TOOLS:-$HOME/.local/nchat-jvm-tools}"
APP="$ROOT/app/src/main/java/com/nchat/app"
NETWORK="$APP/network"
TESTS_ROOT="$ROOT/app/src/test/java/com/nchat/app"
TESTS_DIR="$TESTS_ROOT/network"

MAIN=(E2eeProtocol.kt SecurityCode.kt E2eeAttachment.kt SealedMedia.kt E2eeGroup.kt E2eeStore.kt E2eeArchive.kt E2eeClient.kt E2eeKeys.kt LocalSeal.kt EndToEnd.kt)
ALL_TESTS=(E2eeProtocolTest SecurityCodeTest E2eeAttachmentTest SealedMediaTest E2eeGroupTest E2eeStoreTest E2eeArchiveTest E2eeClientTest E2eeGroupClientTest E2eeKeysTest LocalSealTest EndToEndTest)

# Paths below com/nchat/app, main and test.
APP_MAIN=(
  data/model/Models.kt data/model/MessageDelivery.kt
  data/local/LocalFiles.kt data/local/MessageStore.kt data/local/OutboxStore.kt
  data/local/ChatListCache.kt data/local/GroupListCache.kt data/local/FriendGraphCache.kt
  data/local/DraftStore.kt data/local/StarredStore.kt data/local/PinnedMessageStore.kt data/local/ArchivedStore.kt
  feature/chats/EncryptionMarks.kt feature/chats/ChatPreview.kt core/util/VideoPlan.kt
)
APP_TESTS=(
  data/local/LocalStoresTest data/local/MessageStoreTest data/local/OutboxStoreTest
  feature/chats/EncryptionMarksTest feature/chats/ChatPreviewTest core/util/VideoPlanTest
)

mkdir -p "$TOOLS"

# ---------------------------------------------------------------- toolchain
if [ ! -x "$TOOLS/java" ]; then
  pip download "jdk4py==17.0.9.2" --no-deps --quiet -d "$TOOLS/wheels"
  rm -rf "$TOOLS/jre" && mkdir -p "$TOOLS/jre"
  unzip -q -o "$TOOLS"/wheels/jdk4py-*.whl -d "$TOOLS/jre"
  JAVA_BIN="$(find "$TOOLS/jre" -name java -type f | head -1)"
  chmod +x "$JAVA_BIN"
  ln -sf "$JAVA_BIN" "$TOOLS/java"
fi
if [ ! -f "$TOOLS/kotlin-all.jar" ]; then
  pip download "kotlin-jupyter-kernel==0.12.0.139" --no-deps --quiet -d "$TOOLS/wheels"
  rm -rf "$TOOLS/kernel" && mkdir -p "$TOOLS/kernel"
  unzip -q -o "$TOOLS"/wheels/kotlin_jupyter_kernel-*.whl -d "$TOOLS/kernel"
  # One jar: kotlinc 1.9.10, the stdlib, kotlinx-serialization and coroutines.
  ln -sf "$(find "$TOOLS/kernel" -name 'kotlin-jupyter-kernel-*-all.jar' | head -1)" "$TOOLS/kotlin-all.jar"
fi
JAVA="$TOOLS/java"
JAR="$TOOLS/kotlin-all.jar"

# kotlinx-serialization as jars of their own, ahead of the big one: the compiler
# plugin reads the library's version from the jar it finds the classes in.
SERIALIZATION="$(find "$TOOLS/kernel" -name 'kotlinx-serialization-core-jvm-*.jar' | head -1):$(find "$TOOLS/kernel" -name 'kotlinx-serialization-json-jvm-*.jar' | head -1)"
LIBS="$SERIALIZATION:$JAR"

# The serialization compiler plugin is inside the big jar, registered next to
# others that must not be loaded a second time. A jar that holds nothing but
# its registration makes kotlinc load that one alone.
PLUGIN="$TOOLS/serialization-plugin.jar"
if [ ! -f "$PLUGIN" ]; then
  python3 -c '
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr(
        "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar",
        "org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar\n",
    )
' "$PLUGIN"
fi

kotlinc() { # kotlinc <out-dir> <sources...>
  local out="$1"; shift
  "$JAVA" -Xmx1500m -cp "$JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 "-Xplugin=$PLUGIN" -cp "$LIBS:$out" -d "$out" "$@" 2>&1 \
    | grep -v "^WARNING\|unable to find kotlin" || true
}

# ------------------------- stand-ins for the three Android names of APP_MAIN
STUBS="$TOOLS/stubs"
if [ ! -f "$STUBS/CrashLog.kt" ]; then
  mkdir -p "$STUBS"
  cat > "$STUBS/Context.kt" <<'EOF'
package android.content

import java.io.File

abstract class Context {
    abstract val applicationContext: Context
    abstract val filesDir: File
}
EOF
  cat > "$STUBS/Immutable.kt" <<'EOF'
package androidx.compose.runtime

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY) annotation class Immutable
EOF
  cat > "$STUBS/CrashLog.kt" <<'EOF'
package com.nchat.app.core.util

object CrashLog {
    @Suppress("UNUSED_PARAMETER") fun note(where: String, error: Throwable) = Unit
}
EOF
fi

# ------------------------------------------- a stand-in for JUnit 4 (no jar)
SHIM="$TOOLS/shim"
if [ ! -d "$TOOLS/shim-classes/runner" ]; then
  mkdir -p "$SHIM"
  cat > "$SHIM/JUnit.kt" <<'EOF'
package org.junit

@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Test
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class Before
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.RUNTIME) annotation class After
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME) annotation class Rule

object Assert {
    @JvmStatic fun fail(message: String?): Nothing = throw AssertionError(message ?: "fail()")
    @JvmStatic fun fail(): Nothing = throw AssertionError("fail()")
    @JvmStatic fun assertTrue(condition: Boolean) { if (!condition) throw AssertionError("expected true") }
    @JvmStatic fun assertTrue(message: String?, condition: Boolean) { if (!condition) throw AssertionError(message) }
    @JvmStatic fun assertFalse(condition: Boolean) { if (condition) throw AssertionError("expected false") }
    @JvmStatic fun assertFalse(message: String?, condition: Boolean) { if (condition) throw AssertionError(message) }
    @JvmStatic fun assertNull(value: Any?) { if (value != null) throw AssertionError("expected null but was <$value>") }
    @JvmStatic fun assertNull(message: String?, value: Any?) { if (value != null) throw AssertionError("$message: expected null but was <$value>") }
    @JvmStatic fun assertNotNull(value: Any?) { if (value == null) throw AssertionError("expected non-null") }
    @JvmStatic fun assertNotNull(message: String?, value: Any?) { if (value == null) throw AssertionError(message) }
    @JvmStatic fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String?, expected: Any?, actual: Any?) { if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(expected: Long, actual: Long) { if (expected != actual) throw AssertionError("expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertEquals(message: String?, expected: Long, actual: Long) { if (expected != actual) throw AssertionError("$message expected:<$expected> but was:<$actual>") }
    @JvmStatic fun assertNotEquals(unexpected: Any?, actual: Any?) { if (unexpected == actual) throw AssertionError("values should differ: <$actual>") }
    @JvmStatic fun assertNotEquals(message: String?, unexpected: Any?, actual: Any?) { if (unexpected == actual) throw AssertionError("$message values should differ: <$actual>") }
    @JvmStatic fun assertArrayEquals(expected: ByteArray?, actual: ByteArray?) { if (!(expected contentEquals actual)) throw AssertionError("byte arrays differ") }
    @JvmStatic fun assertArrayEquals(message: String?, expected: ByteArray?, actual: ByteArray?) { if (!(expected contentEquals actual)) throw AssertionError("$message: byte arrays differ") }
    @JvmStatic fun assertSame(expected: Any?, actual: Any?) { if (expected !== actual) throw AssertionError("expected the same instance") }
    @JvmStatic fun assertNotSame(unexpected: Any?, actual: Any?) { if (unexpected === actual) throw AssertionError("expected different instances") }
}
EOF
  mkdir -p "$SHIM/rules" "$SHIM/runner"
  cat > "$SHIM/rules/TemporaryFolder.kt" <<'EOF'
package org.junit.rules

import java.io.File
import java.nio.file.Files

class TemporaryFolder {
    private var folder: File? = null
    val root: File get() = folder ?: error("temporary folder not created")
    fun create() { folder = Files.createTempDirectory("junit").toFile() }
    fun delete() { folder?.deleteRecursively(); folder = null }
    fun newFolder(): File = Files.createTempDirectory(root.toPath(), "junit").toFile()
    fun newFolder(name: String): File = File(root, name).also { it.mkdirs() }
    fun newFile(): File = File.createTempFile("junit", null, root)
    fun newFile(name: String): File = File(root, name).also { it.createNewFile() }
}
EOF
  cat > "$SHIM/runner/Runner.kt" <<'EOF'
package runner

import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var passed = 0
    val failures = mutableListOf<String>()
    for (name in args) {
        val type = Class.forName(name)
        val tests = type.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.sortedBy { it.name }
        for (test in tests) {
            val instance = type.getDeclaredConstructor().newInstance()
            val folders = type.declaredFields
                .filter { it.type == TemporaryFolder::class.java }
                .map { it.isAccessible = true; it.get(instance) as TemporaryFolder }
            try {
                folders.forEach { it.create() }
                type.declaredMethods.filter { it.isAnnotationPresent(Before::class.java) }.forEach { it.invoke(instance) }
                test.invoke(instance)
                passed++
            } catch (error: InvocationTargetException) {
                val cause = error.targetException
                failures += "${type.simpleName}.${test.name}: $cause\n" +
                    cause.stackTrace.filter { it.className.startsWith("com.nchat") }.take(4).joinToString("\n") { "      at $it" }
            } finally {
                runCatching { type.declaredMethods.filter { it.isAnnotationPresent(After::class.java) }.forEach { it.invoke(instance) } }
                folders.forEach { it.delete() }
            }
        }
    }
    println("Tests run: ${passed + failures.size}, Failures: ${failures.size}")
    failures.forEach { println("FAIL $it") }
    if (failures.isNotEmpty()) System.exit(1)
}
EOF
  rm -rf "$TOOLS/shim-classes" && mkdir -p "$TOOLS/shim-classes"
  kotlinc "$TOOLS/shim-classes" "$SHIM/JUnit.kt" "$SHIM/rules/TemporaryFolder.kt" "$SHIM/runner/Runner.kt"
fi

# ------------------------------------------------------------ compile + run
OUT="${NCHAT_JVM_OUT:-$TOOLS/out}"
SOURCE_ROOT="${NCHAT_JVM_MAIN:-$NETWORK}"
APP_ROOT="${NCHAT_JVM_APP:-$APP}"
rm -rf "$OUT" && cp -r "$TOOLS/shim-classes" "$OUT"

if [ "$#" -gt 0 ]; then
  SELECTED=("$@")
else
  SELECTED=("${ALL_TESTS[@]}")
  for test in "${APP_TESTS[@]}"; do SELECTED+=("$(basename "$test")"); done
fi

SOURCES=()
for file in "${MAIN[@]}"; do [ -f "$SOURCE_ROOT/$file" ] && SOURCES+=("$SOURCE_ROOT/$file"); done
CLASSES=()
NEEDS_APP=0
for name in "${SELECTED[@]}"; do
  if [ -f "$TESTS_DIR/$name.kt" ]; then
    SOURCES+=("$TESTS_DIR/$name.kt")
    CLASSES+=("com.nchat.app.network.$name")
    continue
  fi
  for test in "${APP_TESTS[@]}"; do
    [ "$(basename "$test")" = "$name" ] || continue
    [ -f "$TESTS_ROOT/$test.kt" ] || continue
    SOURCES+=("$TESTS_ROOT/$test.kt")
    CLASSES+=("com.nchat.app.$(dirname "$test" | tr / .).$name")
    NEEDS_APP=1
  done
done
if [ "$NEEDS_APP" = 1 ]; then
  SOURCES+=("$STUBS/Context.kt" "$STUBS/Immutable.kt" "$STUBS/CrashLog.kt")
  for file in "${APP_MAIN[@]}"; do [ -f "$APP_ROOT/$file" ] && SOURCES+=("$APP_ROOT/$file"); done
fi
if [ "${#CLASSES[@]}" -eq 0 ]; then
  echo "no such test: ${SELECTED[*]}"
  exit 2
fi

COMPILE_LOG="$(kotlinc "$OUT" "${SOURCES[@]}")"
if echo "$COMPILE_LOG" | grep -q "error:"; then
  echo "$COMPILE_LOG" | grep "error:" | sed "s|$ROOT/||" | head -40
  echo "COMPILATION FAILED"
  exit 2
fi
"$JAVA" -cp "$OUT:$LIBS" runner.RunnerKt "${CLASSES[@]}"
