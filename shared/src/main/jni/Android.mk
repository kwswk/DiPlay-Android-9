LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := xcertplay_i2c
LOCAL_SRC_FILES := linux_i2c_jni.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

include $(LOCAL_PATH)/opus/opus_sources.mk
include $(LOCAL_PATH)/opus/celt_sources.mk
include $(LOCAL_PATH)/opus/silk_sources.mk
include $(CLEAR_VARS)
LOCAL_MODULE := diplay_opus
LOCAL_SRC_FILES := opus_jni.c $(addprefix opus/,$(OPUS_SOURCES) $(OPUS_SOURCES_FLOAT) $(CELT_SOURCES) $(SILK_SOURCES) $(SILK_SOURCES_FLOAT))
LOCAL_C_INCLUDES := $(LOCAL_PATH)/opus/include $(LOCAL_PATH)/opus/src $(LOCAL_PATH)/opus/celt $(LOCAL_PATH)/opus/silk $(LOCAL_PATH)/opus/silk/float
LOCAL_CFLAGS := -O2 -DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINT -DHAVE_LRINTF
LOCAL_LDLIBS := -lm
include $(BUILD_SHARED_LIBRARY)
