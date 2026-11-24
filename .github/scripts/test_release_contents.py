"""Tests for release_contents.py — what the release APK must and must not carry."""
import io
import struct
import unittest
import zipfile

import release_contents as contents


def uleb128(value):
    out = bytearray()
    while True:
        byte = value & 0x7f
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def dex(defined, referenced=()):
    """A dex file that defines `defined` and merely mentions `referenced` — only the parts that are read."""
    names = sorted(set(defined) | set(referenced))
    strings = [("L" + name.replace(".", "/") + ";").encode("utf-8") for name in names]
    header_size = 0x70
    string_ids_off = header_size
    type_ids_off = string_ids_off + 4 * len(strings)
    class_defs_off = type_ids_off + 4 * len(strings)
    data_off = class_defs_off + 32 * len(defined)
    data = b""
    offsets = []
    for text in strings:
        offsets.append(data_off + len(data))
        data += uleb128(len(text)) + text + b"\x00"
    out = bytearray(b"dex\n035\x00" + bytes(header_size - 8))
    struct.pack_into("<II", out, 0x38, len(strings), string_ids_off)
    struct.pack_into("<II", out, 0x40, len(strings), type_ids_off)
    struct.pack_into("<II", out, 0x60, len(defined), class_defs_off)
    out += b"".join(struct.pack("<I", offset) for offset in offsets)
    out += b"".join(struct.pack("<I", index) for index in range(len(strings)))
    for name in defined:
        out += struct.pack("<I", names.index(name)) + bytes(28)
    return bytes(out + data)


def apk(*dex_files, extra=()):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for index, content in enumerate(dex_files):
            archive.writestr("classes.dex" if index == 0 else f"classes{index + 1}.dex", content)
        archive.writestr("AndroidManifest.xml", b"\x03\x00")
        for name, content in extra:
            archive.writestr(name, content)
    buffer.seek(0)
    return buffer


class DefinedClasses(unittest.TestCase):
    def test_reads_the_classes_a_dex_file_defines(self):
        content = dex(["com.nchat.app.MainActivity", "com.nchat.app.network.UserDto$$serializer"])
        self.assertEqual(
            contents.defined_classes(content),
            {"com.nchat.app.MainActivity", "com.nchat.app.network.UserDto$$serializer"},
        )

    def test_a_class_that_is_only_mentioned_is_not_there(self):
        # What a release lacks is what a search for its name would still find: the callers name it.
        content = dex(["com.nchat.app.MainActivity"], referenced=["org.webrtc.JniHelper"])
        self.assertEqual(contents.defined_classes(content), {"com.nchat.app.MainActivity"})
        self.assertIn(b"Lorg/webrtc/JniHelper;", content)

    def test_long_names_are_read_whole(self):
        long = "com.example." + "deeply.nested." * 12 + "Name"
        self.assertGreater(len(long), 127, "long enough for its length to take two bytes")
        self.assertEqual(contents.defined_classes(dex([long, "a.B"])), {long, "a.B"})

    def test_what_is_not_a_dex_file_is_refused(self):
        for junk in (b"", b"PK\x03\x04", b"dex\n035\x00", bytes(200)):
            with self.assertRaises(ValueError):
                contents.defined_classes(junk)

    def test_every_dex_file_of_an_apk_counts(self):
        archive = apk(dex(["a.One"]), dex(["b.Two", "b.Three"]), extra=[("assets/not-classes.dex.txt", b"x"), ("lib/x86/classes.dex.so", b"x")])
        self.assertEqual(contents.classes_in_apk(archive), {"a.One", "b.Two", "b.Three"})

    def test_an_apk_without_code_is_refused(self):
        with self.assertRaises(ValueError):
            contents.classes_in_apk(apk())


class Rules(unittest.TestCase):
    def everything(self):
        return set(contents.REQUIRED)

    def test_a_release_with_what_it_needs_and_nothing_unused_passes(self):
        self.assertEqual(contents.problems(self.everything() | {"androidx.compose.material.icons.rounded.SendKt"}), [])

    def test_each_class_only_reached_by_name_is_required(self):
        # Reached from the manifest, a layout file, native code or a service file: nothing in
        # the code says so, and shrinking that does not know would take them out.
        for name in (
            "com.nchat.app.MainActivity",
            "com.nchat.app.network.UserDto$$serializer",
            "org.webrtc.WebRtcClassLoader",
            "com.journeyapps.barcodescanner.CaptureActivity",
            "com.journeyapps.barcodescanner.DecoratedBarcodeView",
            "kotlinx.coroutines.android.AndroidDispatcherFactory",
        ):
            self.assertIn(name, contents.REQUIRED)
            found = contents.problems(self.everything() - {name})
            self.assertEqual(len(found), 1, found)
            self.assertIn(name, found[0])
            self.assertIn("missing", found[0])

    def test_code_nothing_uses_must_not_be_shipped(self):
        # The app draws icons of one family; a release that carries the others carries
        # thousands of classes nobody will ever see, in every download.
        for family in ("filled", "outlined", "sharp", "twotone"):
            unused = f"androidx.compose.material.icons.{family}.AcUnitKt"
            found = contents.problems(self.everything() | {unused})
            self.assertEqual(len(found), 1, found)
            self.assertIn(unused, found[0])
            self.assertIn("not used", found[0])

    def test_everything_wrong_is_said_at_once(self):
        classes = self.everything() - {"org.webrtc.JniHelper", "okhttp3.OkHttpClient"}
        classes |= {"androidx.compose.material.icons.sharp.AcUnitKt"}
        self.assertEqual(len(contents.problems(classes)), 3)


class Summary(unittest.TestCase):
    def test_says_size_and_class_count_in_one_line(self):
        line = contents.summary(24_563_112, 18_211)
        self.assertEqual(line, "23.4 MB, 18211 classes")
        self.assertEqual(contents.summary(31_131_359, 93_402), "29.7 MB, 93402 classes")


if __name__ == "__main__":
    unittest.main()
