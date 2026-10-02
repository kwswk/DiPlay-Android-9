#include <jni.h>
#include <stdint.h>
#include "opus.h"

JNIEXPORT jlong JNICALL
Java_com_shilapi_xcertplay_media_SoftwareOpusEncoder_create(JNIEnv *env, jobject self, jint bitrate) {
    (void)env; (void)self;
    int error;
    OpusEncoder *encoder = opus_encoder_create(48000, 1, OPUS_APPLICATION_VOIP, &error);
    if (!encoder || error != OPUS_OK) return 0;
    if (opus_encoder_ctl(encoder, OPUS_SET_BITRATE(bitrate)) != OPUS_OK) {
        opus_encoder_destroy(encoder);
        return 0;
    }
    return (jlong)(intptr_t)encoder;
}

JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_media_SoftwareOpusEncoder_encode(JNIEnv *env, jobject self, jlong handle, jbyteArray input) {
    (void)self;
    if (!handle || (*env)->GetArrayLength(env, input) != 1920) return NULL;
    unsigned char bytes[1920], packet[1275];
    opus_int16 samples[960];
    (*env)->GetByteArrayRegion(env, input, 0, 1920, (jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) return NULL;
    for (int i = 0; i < 960; i++) samples[i] = (opus_int16)(bytes[i*2] | (bytes[i*2+1] << 8));
    int count = opus_encode((OpusEncoder *)(intptr_t)handle, samples, 960, packet, sizeof(packet));
    if (count < 0) {
        jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
        if (error) (*env)->ThrowNew(env, error, opus_strerror(count));
        return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, count);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, count, (jbyte *)packet);
    return result;
}

JNIEXPORT void JNICALL
Java_com_shilapi_xcertplay_media_SoftwareOpusEncoder_destroy(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    if (handle) opus_encoder_destroy((OpusEncoder *)(intptr_t)handle);
}
