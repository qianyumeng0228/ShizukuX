#!/usr/bin/env python3
"""Fail a release build when R8 renamed the JNI surface that libadb.so binds by name.

rust/adb-pairing/src/lib.rs resolves its natives at JNI_OnLoad with hard-coded names:

    PAIRING_CONTEXT_CLASS   = "af/shizuku/manager/adb/PairingContext"
    ADB_PAIRING_CLIENT_CLASS = "af/shizuku/manager/adb/AdbPairingClient"
    nativeConstructor (Z[B)J / nativeMsg (J)[B / nativeInitCipher (J[B)Z
    nativeEncrypt (J[B)[B / nativeDecrypt (J[B)[B / nativeDestroy (J)V
    available ()Z

If ProGuard/R8 renames either class or any of those members, RegisterNatives fails,
JNI_OnLoad returns JNI_ERR and every System.loadLibrary("adb") call throws
UnsatisfiedLinkError. Release builds then lose ADB pairing entirely while debug builds
(no shrinking) keep working — the failure only shows up on a device, at pairing time.

This shipped in 13.6.0.r2678. The check below runs on the built APK, so the same class
of regression fails CI instead of reaching users.

Usage: verify_jni_symbols.py <apk> [<apk> ...]
"""

import struct
import sys
import zipfile

ACC_NATIVE = 0x0100

PAIRING_CONTEXT = "Laf/shizuku/manager/adb/PairingContext;"
ADB_PAIRING_CLIENT = "Laf/shizuku/manager/adb/AdbPairingClient;"

# (class descriptor, method name, expected JNI signature)
EXPECTED = [
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeConstructor", "(Z[B)J"),
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeMsg", "(J)[B"),
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeInitCipher", "(J[B)Z"),
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeEncrypt", "(J[B)[B"),
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeDecrypt", "(J[B)[B"),
    ("Laf/shizuku/manager/adb/PairingContext;", "nativeDestroy", "(J)V"),
    ("Laf/shizuku/manager/adb/AdbPairingClient;", "available", "()Z"),
]


class Dex:
    def __init__(self, data):
        self.data = data
        self.s_size, self.s_off, = struct.unpack_from("<II", data, 0x38)
        self.t_size, self.t_off = struct.unpack_from("<II", data, 0x40)
        self.p_size, self.p_off = struct.unpack_from("<II", data, 0x48)
        self.m_size, self.m_off = struct.unpack_from("<II", data, 0x58)
        self.c_size, self.c_off = struct.unpack_from("<II", data, 0x60)

    def uleb(self, pos):
        result = 0
        shift = 0
        while True:
            b = self.data[pos]
            pos += 1
            result |= (b & 0x7F) << shift
            if not (b & 0x80):
                return result, pos
            shift += 7

    def string(self, idx):
        off = struct.unpack_from("<I", self.data, self.s_off + idx * 4)[0]
        pos = off
        _, pos = self.uleb(pos)
        end = pos
        while self.data[end] != 0:
            end += 1
        return self.data[pos:end].decode("utf-8", "ignore")

    def type_name(self, idx):
        return self.string(struct.unpack_from("<I", self.data, self.t_off + idx * 4)[0])

    def descriptor_of(self, type_idx):
        return self.type_name(type_idx)

    def proto_signature(self, proto_idx):
        base = self.p_off + proto_idx * 12
        _, return_idx, params_off = struct.unpack_from("<III", self.data, base)
        args = []
        if params_off:
            size = struct.unpack_from("<I", self.data, params_off)[0]
            for i in range(size):
                type_idx = struct.unpack_from("<H", self.data, params_off + 4 + i * 2)[0]
                args.append(self.descriptor_of(type_idx))
        return "(" + "".join(args) + ")" + self.descriptor_of(return_idx)

    def method_entry(self, method_idx):
        base = self.m_off + method_idx * 8
        _, proto_idx, name_idx = struct.unpack_from("<HHI", self.data, base)
        return self.string(name_idx), self.proto_signature(proto_idx)

    def methods_of(self, wanted_descriptor):
        """Yields (name, signature, access_flags) for every method defined on the class."""
        for i in range(self.c_size):
            base = self.c_off + i * 32
            class_idx = struct.unpack_from("<I", self.data, base)[0]
            if self.type_name(class_idx) != wanted_descriptor:
                continue
            class_data_off = struct.unpack_from("<I", self.data, base + 24)[0]
            if class_data_off == 0:
                return
            pos = class_data_off
            static_fields, pos = self.uleb(pos)
            instance_fields, pos = self.uleb(pos)
            direct, pos = self.uleb(pos)
            virtual, pos = self.uleb(pos)
            for _ in range(static_fields + instance_fields):
                _, pos = self.uleb(pos)
                _, pos = self.uleb(pos)
            method_idx = 0
            for count in (direct, virtual):
                method_idx = 0
                for _ in range(count):
                    diff, pos = self.uleb(pos)
                    method_idx += diff
                    flags, pos = self.uleb(pos)
                    _, pos = self.uleb(pos)
                    name, sig = self.method_entry(method_idx)
                    yield name, sig, flags
            return


def verify(path):
    problems = []
    with zipfile.ZipFile(path) as apk:
        dex_names = [n for n in apk.namelist()
                     if n.endswith(".dex") and not n.startswith("assets/")]
        if not dex_names:
            problems.append("no classes*.dex found in %s" % path)
            return problems
        found = {}
        for name in dex_names:
            dex = Dex(apk.read(name))
            for descriptor, method_name, expected_sig in EXPECTED:
                for m_name, m_sig, flags in dex.methods_of(descriptor):
                    if m_name == method_name and flags & ACC_NATIVE:
                        found[(descriptor, method_name)] = m_sig

    if not any(k[0] == PAIRING_CONTEXT for k in found) and \
            not any(k[0] == ADB_PAIRING_CLIENT for k in found):
        problems.append(
            "neither PairingContext nor AdbPairingClient defines a native method — "
            "R8 removed or renamed the JNI surface entirely")
        return problems

    for descriptor, method_name, expected_sig in EXPECTED:
        key = (descriptor, method_name)
        actual = found.get(key)
        short = descriptor[1:-1].replace("/", ".")
        if actual is None:
            problems.append("%s.%s: native method missing (renamed or removed by R8)"
                            % (short, method_name))
        elif actual != expected_sig:
            problems.append("%s.%s: signature %s, libadb.so registers %s"
                            % (short, method_name, actual, expected_sig))
    return problems


def main(paths):
    # CI passes both the main and the drop-in APK; a variant that was not built
    # arrives as an empty string and must not be treated as a missing file.
    paths = [p for p in paths if p and p.strip()]
    if not paths:
        print("no APK path given")
        return 2
    failed = False
    for path in paths:
        problems = verify(path)
        if problems:
            failed = True
            print("FAIL %s" % path)
            for problem in problems:
                print("  - %s" % problem)
        else:
            print("OK   %s (JNI surface intact)" % path)
    if failed:
        print("\nADB pairing would crash at runtime: JNI_OnLoad would return JNI_ERR.")
        print("Add a keep rule in manager/proguard-rules.pro for these classes.")
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
