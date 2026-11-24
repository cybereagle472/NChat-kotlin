"""What the release APK carries — and what it must not.

The release is shrunk: code that nothing uses is left out, which is most of
what the libraries bring (the app draws a few hundred icons out of several
thousand). Shrinking follows what the code calls. It cannot see what is
reached by name only — an activity from the manifest, a view from a layout
file, a class that native code calls back into, a serializer found by its
name — and without a rule for it, such a class is simply gone, and the app
falls over on the phone the first time it is needed.

So this looks into the APK that was built, at the classes it defines:

    REQUIRED    one class for each way of being reached by name only; every
                one must be there.
    UNUSED      classes of icon families the app never draws; none may be
                there — if one is, nothing was shrunk.

    release_contents.py <apk>

Says size and number of classes as a notice on the job; exit status 0 only
when both lists hold. It reads the definitions in the dex files, not their
strings: a class that was removed is still named by whoever called it.
"""
import struct
import sys
import zipfile
from pathlib import Path

REQUIRED = [
    # The manifest names these.
    "com.nchat.app.NChatApplication",
    "com.nchat.app.MainActivity",
    "com.journeyapps.barcodescanner.CaptureActivity",
    # A layout file of the scanning library names this one.
    "com.journeyapps.barcodescanner.DecoratedBarcodeView",
    # The app's own code, and a serializer the compiler generated for it.
    "com.nchat.app.network.E2eeProtocol",
    "com.nchat.app.network.UserDto$$serializer",
    # Calls: the native library calls back into these by name.
    "org.webrtc.PeerConnectionFactory",
    "org.webrtc.WebRtcClassLoader",
    "org.webrtc.JniHelper",
    # Found through a service file at start-up; without it there is no main thread to dispatch to.
    "kotlinx.coroutines.android.AndroidDispatcherFactory",
    "okhttp3.OkHttpClient",
    "androidx.media3.transformer.Transformer",
]

# The app draws `Icons.Rounded` only.
UNUSED = [f"androidx.compose.material.icons.{family}.AcUnitKt" for family in ("filled", "outlined", "sharp", "twotone")]


def _uleb128(data, at):
    value = 0
    shift = 0
    while True:
        byte = data[at]
        at += 1
        value |= (byte & 0x7f) << shift
        if not byte & 0x80:
            return value, at
        shift += 7


def defined_classes(dex):
    """The names of the classes a dex file defines. Raises ValueError for what is not a dex file."""
    if len(dex) < 0x70 or dex[:4] != b"dex\n":
        raise ValueError("not a dex file")
    try:
        strings_size, strings_off = struct.unpack_from("<II", dex, 0x38)
        types_size, types_off = struct.unpack_from("<II", dex, 0x40)
        classes_size, classes_off = struct.unpack_from("<II", dex, 0x60)
        names = set()
        for index in range(classes_size):
            (type_index,) = struct.unpack_from("<I", dex, classes_off + 32 * index)
            if type_index >= types_size:
                raise ValueError("a class of a type that is not listed")
            (string_index,) = struct.unpack_from("<I", dex, types_off + 4 * type_index)
            if string_index >= strings_size:
                raise ValueError("a type with a name that is not listed")
            (data_off,) = struct.unpack_from("<I", dex, strings_off + 4 * string_index)
            _, start = _uleb128(dex, data_off)
            descriptor = dex[start:dex.index(b"\x00", start)].decode("utf-8", errors="replace")
            names.add(descriptor[1:-1].replace("/", ".") if descriptor.startswith("L") and descriptor.endswith(";") else descriptor)
        return names
    except (struct.error, IndexError) as error:
        raise ValueError(f"a dex file that ends too early: {error}") from error


def classes_in_apk(apk):
    """Every class the APK defines, over all its dex files. `apk` is a path or a file object."""
    found = set()
    with zipfile.ZipFile(apk) as archive:
        dex_files = [name for name in archive.namelist() if "/" not in name and name.startswith("classes") and name.endswith(".dex")]
        if not dex_files:
            raise ValueError("an APK without code")
        for name in dex_files:
            found |= defined_classes(archive.read(name))
    return found


def problems(classes):
    """What is wrong with a release that defines `classes`; empty when nothing is."""
    found = [f"missing: {name} — it is reached by name only, and the app needs it" for name in REQUIRED if name not in classes]
    found += [f"shipped although not used: {name} — the release is not shrunk" for name in UNUSED if name in classes]
    return found


def summary(apk_bytes, class_count):
    return f"{apk_bytes / (1024 * 1024):.1f} MB, {class_count} classes"


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    apk = Path(argv[1])
    classes = classes_in_apk(apk)
    wrong = problems(classes)
    line = summary(apk.stat().st_size, len(classes))
    if wrong:
        print(f"::error title=Release APK contents::{line}; {len(wrong)} problem(s): {wrong[0]}")
        for problem in wrong:
            print(problem)
        return 1
    print(f"::notice title=Release APK contents::{line}; everything reached by name is there, nothing of the unused icon families is")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
