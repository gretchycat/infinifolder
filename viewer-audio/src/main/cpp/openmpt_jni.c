#include <jni.h>
#include <libopenmpt/libopenmpt.h>
#include <stdlib.h>
#include <string.h>

#define CHUNK_FRAMES 4096

JNIEXPORT jboolean JNICALL
Java_com_infinifolder_audio_tracker_OpenMpt_nativeIsAvailable(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_infinifolder_audio_tracker_OpenMpt_nativeProbe(JNIEnv *env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (!data) return JNI_FALSE;
    jsize len = (*env)->GetArrayLength(env, data);
    if (len < 64) return JNI_FALSE;

    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return JNI_FALSE;

    int res = openmpt_probe_file_header_without_filesize(
        OPENMPT_PROBE_FILE_HEADER_FLAGS_MODULES,
        bytes,
        (size_t)len,
        NULL, NULL, NULL, NULL, NULL, NULL
    );
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    return (res == OPENMPT_PROBE_FILE_HEADER_RESULT_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_infinifolder_audio_tracker_OpenMpt_nativeGetMetadata(JNIEnv *env, jclass clazz, jbyteArray data, jstring jkey) {
    (void)clazz;
    if (!data || !jkey) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return NULL;

    const char *key = (*env)->GetStringUTFChars(env, jkey, NULL);
    openmpt_module *mod = openmpt_module_create_from_memory2(
        bytes, (size_t)len, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    );
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    jstring result = NULL;
    if (mod) {
        const char *val = openmpt_module_get_metadata(mod, key);
        if (val && strlen(val) > 0) {
            result = (*env)->NewStringUTF(env, val);
        }
        openmpt_module_destroy(mod);
    }
    (*env)->ReleaseStringUTFChars(env, jkey, key);
    return result;
}

JNIEXPORT jdouble JNICALL
Java_com_infinifolder_audio_tracker_OpenMpt_nativeGetDurationSeconds(JNIEnv *env, jclass clazz, jbyteArray data) {
    (void)clazz;
    if (!data) return 0.0;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return 0.0;

    openmpt_module *mod = openmpt_module_create_from_memory2(
        bytes, (size_t)len, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    );
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);

    double dur = 0.0;
    if (mod) {
        dur = openmpt_module_get_duration_seconds(mod);
        openmpt_module_destroy(mod);
    }
    return dur;
}

JNIEXPORT jshortArray JNICALL
Java_com_infinifolder_audio_tracker_OpenMpt_nativeRenderToPcm(
    JNIEnv *env, jclass clazz, jbyteArray data, jint sample_rate, jlong max_duration_ms) {
    (void)clazz;
    if (!data || sample_rate <= 0) return NULL;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (!bytes) return NULL;

    openmpt_module *mod = openmpt_module_create_from_memory2(
        bytes, (size_t)len, NULL, NULL, NULL, NULL, NULL, NULL, NULL
    );
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (!mod) return NULL;

    double dur_sec = openmpt_module_get_duration_seconds(mod);
    if (dur_sec <= 0.0 || dur_sec > 600.0) {
        dur_sec = (max_duration_ms > 0) ? (max_duration_ms / 1000.0) : 60.0;
    }
    if (max_duration_ms > 0 && dur_sec > (max_duration_ms / 1000.0)) {
        dur_sec = max_duration_ms / 1000.0;
    }

    size_t total_frames = (size_t)(dur_sec * sample_rate);
    if (total_frames == 0) {
        openmpt_module_destroy(mod);
        return NULL;
    }

    jshortArray result = (*env)->NewShortArray(env, (jsize)(total_frames * 2));
    if (!result) {
        openmpt_module_destroy(mod);
        return NULL;
    }

    int16_t buffer[CHUNK_FRAMES * 2];
    size_t frames_rendered = 0;

    while (frames_rendered < total_frames) {
        size_t to_render = total_frames - frames_rendered;
        if (to_render > CHUNK_FRAMES) to_render = CHUNK_FRAMES;

        size_t actual = openmpt_module_read_interleaved_stereo(mod, sample_rate, to_render, buffer);
        if (actual == 0) break;

        (*env)->SetShortArrayRegion(env, result, (jsize)(frames_rendered * 2), (jsize)(actual * 2), (jshort *)buffer);
        frames_rendered += actual;
    }

    openmpt_module_destroy(mod);

    if (frames_rendered < total_frames && frames_rendered > 0) {
        jshortArray trimmed = (*env)->NewShortArray(env, (jsize)(frames_rendered * 2));
        if (trimmed) {
            jshort *elems = (*env)->GetShortArrayElements(env, result, NULL);
            if (elems) {
                (*env)->SetShortArrayRegion(env, trimmed, 0, (jsize)(frames_rendered * 2), elems);
                (*env)->ReleaseShortArrayElements(env, result, elems, JNI_ABORT);
                return trimmed;
            }
        }
    }

    return result;
}
