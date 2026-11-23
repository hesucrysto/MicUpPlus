/*
 * Trimmed ABI header for Android audio effect libraries.
 * Layouts copied from AOSP hardware/libhardware/include/hardware/audio_effect.h
 * and system/media/audio/include/system/audio_effect.h (Apache-2.0).
 *
 * The NDK does not ship these headers, and the structures are stable ABI, so
 * they live here verbatim. Do not reorder fields.
 */
#ifndef MICUP_AUDIO_EFFECT_ABI_H
#define MICUP_AUDIO_EFFECT_ABI_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define EFFECT_MAKE_API_VERSION(M, m) (((M) << 16) | ((m) & 0xFFFF))
#define EFFECT_CONTROL_API_VERSION EFFECT_MAKE_API_VERSION(2, 0)
#define EFFECT_LIBRARY_API_VERSION EFFECT_MAKE_API_VERSION(3, 0)

#define AUDIO_EFFECT_LIBRARY_TAG ((('A') << 24) | (('E') << 16) | (('L') << 8) | ('T'))
#define AUDIO_EFFECT_LIBRARY_INFO_SYM AELI
#define AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR "AELI"

#define EFFECT_STRING_LEN_MAX 64

typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t  node[6];
} effect_uuid_t;

typedef struct effect_descriptor_s {
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;
    uint16_t memoryUsage;
    char     name[EFFECT_STRING_LEN_MAX];
    char     implementor[EFFECT_STRING_LEN_MAX];
} effect_descriptor_t;

/* Connection mode: 3 = preprocessing, applied on the capture path. */
#define EFFECT_FLAG_TYPE_SHIFT 0
#define EFFECT_FLAG_TYPE_PRE_PROC (3 << EFFECT_FLAG_TYPE_SHIFT)

#define EFFECT_FLAG_INSERT_SHIFT 3
#define EFFECT_FLAG_INSERT_FIRST (1 << EFFECT_FLAG_INSERT_SHIFT)

#define EFFECT_FLAG_INPUT_SHIFT 12
#define EFFECT_FLAG_INPUT_DIRECT (1 << EFFECT_FLAG_INPUT_SHIFT)

#define EFFECT_FLAG_OUTPUT_SHIFT 14
#define EFFECT_FLAG_OUTPUT_DIRECT (1 << EFFECT_FLAG_OUTPUT_SHIFT)

typedef struct audio_buffer_s {
    size_t frameCount;
    union {
        void*    raw;
        float*   f32;
        int32_t* s32;
        int16_t* s16;
        uint8_t* u8;
    };
} audio_buffer_t;

typedef int32_t (*buffer_function_t)(void* cookie, audio_buffer_t* buffer);

typedef struct buffer_provider_s {
    buffer_function_t getBuffer;
    buffer_function_t releaseBuffer;
    void*             cookie;
} buffer_provider_t;

typedef struct buffer_config_s {
    audio_buffer_t    buffer;
    uint32_t          samplingRate;
    uint32_t          channels;      /* channel mask, not count */
    buffer_provider_t bufferProvider;
    uint8_t           format;        /* audio_format_t, truncated to 8 bits */
    uint8_t           accessMode;
    uint16_t          mask;
} buffer_config_t;

typedef enum {
    EFFECT_BUFFER_ACCESS_WRITE = 0,
    EFFECT_BUFFER_ACCESS_READ = 1,
    EFFECT_BUFFER_ACCESS_ACCUMULATE = 2
} effect_buffer_access_e;

typedef struct effect_config_s {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

typedef struct effect_param_s {
    int32_t  status;
    uint32_t psize;
    uint32_t vsize;
    char     data[];
} effect_param_t;

#define EFFECT_CONFIG_BUFFER   0x0001
#define EFFECT_CONFIG_SMP_RATE 0x0002
#define EFFECT_CONFIG_CHANNELS 0x0004
#define EFFECT_CONFIG_FORMAT   0x0008
#define EFFECT_CONFIG_ACC_MODE 0x0010
#define EFFECT_CONFIG_PROVIDER 0x0020

#define AUDIO_FORMAT_PCM_16_BIT 0x1u
#define AUDIO_FORMAT_PCM_32_BIT 0x3u
#define AUDIO_FORMAT_PCM_8_24_BIT 0x4u
#define AUDIO_FORMAT_PCM_FLOAT  0x5u

#define AUDIO_CHANNEL_IN_MONO   0x10u
#define AUDIO_CHANNEL_IN_STEREO 0x0Cu

enum effect_command_e {
    EFFECT_CMD_INIT = 0,
    EFFECT_CMD_SET_CONFIG = 1,
    EFFECT_CMD_RESET = 2,
    EFFECT_CMD_ENABLE = 3,
    EFFECT_CMD_DISABLE = 4,
    EFFECT_CMD_SET_PARAM = 5,
    EFFECT_CMD_SET_PARAM_DEFERRED = 6,
    EFFECT_CMD_SET_PARAM_COMMIT = 7,
    EFFECT_CMD_GET_PARAM = 8,
    EFFECT_CMD_SET_DEVICE = 9,
    EFFECT_CMD_SET_VOLUME = 10,
    EFFECT_CMD_SET_AUDIO_MODE = 11,
    EFFECT_CMD_SET_CONFIG_REVERSE = 12,
    EFFECT_CMD_SET_INPUT_DEVICE = 13,
    EFFECT_CMD_GET_CONFIG = 14,
    EFFECT_CMD_GET_CONFIG_REVERSE = 15,
    EFFECT_CMD_GET_FEATURE_SUPPORTED_CONFIGS = 16,
    EFFECT_CMD_GET_FEATURE_CONFIG = 17,
    EFFECT_CMD_SET_FEATURE_CONFIG = 18,
    EFFECT_CMD_SET_AUDIO_SOURCE = 19,
    EFFECT_CMD_OFFLOAD = 20,
    EFFECT_CMD_FIRST_PROPRIETARY = 0x10000
};

typedef struct effect_interface_s** effect_handle_t;

struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t* inBuffer, audio_buffer_t* outBuffer);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
                       void* pCmdData, uint32_t* replySize, void* pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t* pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t* inBuffer, audio_buffer_t* outBuffer);
};

typedef struct audio_effect_library_s {
    uint32_t tag;
    uint32_t version;
    const char* name;
    const char* implementor;
    int32_t (*create_effect)(const effect_uuid_t* uuid, int32_t sessionId,
                             int32_t ioId, effect_handle_t* pHandle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t* uuid, effect_descriptor_t* pDescriptor);
} audio_effect_library_t;

#ifdef __cplusplus
}
#endif

#endif /* MICUP_AUDIO_EFFECT_ABI_H */
