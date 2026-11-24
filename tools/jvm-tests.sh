#!/usr/bin/env bash
#
# Runs the plain-JVM unit tests of the end-to-end encryption code without
# Gradle or the Android SDK — minutes of CI become seconds on any machine
# that has Python's pip.
#
#   tools/jvm-tests.sh                 compile and run every E2EE test
#   tools/jvm-tests.sh SomeTest ...    run only the named test classes
#
# It is a development aid, not the gate: CI (`:app:testDebugUnitTest`) builds
# with the project's own Kotlin version against android.jar. This script uses
# kotlinc 1.9.10 on a desktop JRE 17 and compiles only the files listed in
# MAIN below, which must stay free of `android.*` imports.
#
# First run downloads a JRE and a Kotlin compiler from PyPI (about 120 MB)
# into $NCHAT_JVM_TOOLS (default ~/.local/nchat-jvm-tools).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="${NCHAT_JVM_TOOLS:-$HOME/.local/nchat-jvm-tools}"
NETWORK="$ROOT/app/src/main/java/com/nchat/app/network"
TESTS_DIR="$ROOT/app/src/test/java/com/nchat/app/network"

MAIN=(E2eeProtocol.kt SecurityCode.kt E2eeAttachment.kt SealedMedia.kt E2eeGroup.kt E2eeStore.kt E2eeArchive.kt E2eeClient.kt E2eeKeys.kt EndToEnd.kt)
ALL_TESTS=(E2eeProtocolTest SecurityCodeTest E2eeAttachmentTest SealedMediaTest E2eeGroupTest E2eeStoreTest E2eeArchiveTest E2eeClientTest E2eeGroupClientTest E2eeKeysTest EndToEndTest)

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

kotlinc() { # kotlinc <out-dir> <sources...>
  local out="$1"; shift
  "$JAVA" -Xmx1500m -cp "$JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -jvm-target 17 -cp "$JAR:$out" -d "$out" "$@" 2>&1 \
    | grep -v "^WARNING\|unable to find kotlin" || true
}

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
rm -rf "$OUT" && cp -r "$TOOLS/shim-classes" "$OUT"

if [ "$#" -gt 0 ]; then SELECTED=("$@"); else SELECTED=("${ALL_TESTS[@]}"); fi
SOURCES=()
for file in "${MAIN[@]}"; do [ -f "$SOURCE_ROOT/$file" ] && SOURCES+=("$SOURCE_ROOT/$file"); done
CLASSES=()
for test in "${SELECTED[@]}"; do
  [ -f "$TESTS_DIR/$test.kt" ] || continue
  SOURCES+=("$TESTS_DIR/$test.kt")
  CLASSES+=("com.nchat.app.network.$test")
done

COMPILE_LOG="$(kotlinc "$OUT" "${SOURCES[@]}")"
if echo "$COMPILE_LOG" | grep -q "error:"; then
  echo "$COMPILE_LOG" | grep "error:" | sed "s|$ROOT/||" | head -40
  echo "COMPILATION FAILED"
  exit 2
fi
"$JAVA" -cp "$OUT:$JAR" runner.RunnerKt "${CLASSES[@]}"
