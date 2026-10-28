#!/bin/sh
set -eu
native_src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
native_output=$1
native_java_home=$2
mkdir -p "$native_output/arm64-v8a"

clang -std=gnu11 -O2 -fPIC -shared -Wall -Wextra \
    -I/data/data/com.termux/files/usr/include \
    -I"$native_java_home/include" -I"$native_java_home/include/linux" \
    "$native_src/openmpt_jni.c" \
    -lopenmpt -Wl,-z,max-page-size=16384 -Wl,-soname,libinfiniopenmpt.so \
    -o "$native_output/arm64-v8a/libinfiniopenmpt.so"

cp /data/data/com.termux/files/usr/lib/libopenmpt.so \
   /data/data/com.termux/files/usr/lib/libmpg123.so \
   /data/data/com.termux/files/usr/lib/libvorbis.so \
   /data/data/com.termux/files/usr/lib/libvorbisfile.so \
   /data/data/com.termux/files/usr/lib/libogg.so \
   /data/data/com.termux/files/usr/lib/libc++_shared.so \
   "$native_output/arm64-v8a/"

patchelf --replace-needed libz.so.1 libz.so "$native_output/arm64-v8a/libopenmpt.so"

for lib in "$native_output/arm64-v8a/"*.so; do
    patchelf --remove-rpath "$lib" || true
done
