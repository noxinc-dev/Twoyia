/*
 * Twoyi Virtualization Platform - Hardware Abstraction Layer (HAL) IPC Protocol
 * Defines communication structures between Guest OS HAL modules and Host Android services.
 */

#ifndef TWOYI_HAL_IPC_H
#define TWOYI_HAL_IPC_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Abstract UNIX domain socket names used for communication */
#define TWOYI_AUDIO_SOCK_NAME     "TWOYI_AUDIO_SOCK"
#define TWOYI_MIC_SOCK_NAME       "TWOYI_MIC_SOCK"
#define TWOYI_CAMERA_SOCK_NAME    "TWOYI_CAMERA_SOCK"

/* Magic values for packet validation */
#define TWOYI_AUDIO_MAGIC         0x54574155 /* "TWAU" */
#define TWOYI_MIC_MAGIC           0x54574D43 /* "TWMC" */
#define TWOYI_CAMERA_MAGIC        0x5457434D /* "TWCM" */

/* Audio HAL commands */
enum TwoyiAudioCmd {
    TWOYI_AUDIO_CMD_INIT = 1,
    TWOYI_AUDIO_CMD_WRITE_DATA = 2,
    TWOYI_AUDIO_CMD_SET_VOLUME = 3,
    TWOYI_AUDIO_CMD_PAUSE = 4,
    TWOYI_AUDIO_CMD_RESUME = 5,
    TWOYI_AUDIO_CMD_CLOSE = 6
};

/* Audio packet header */
struct TwoyiAudioHeader {
    uint32_t magic;         /* TWOYI_AUDIO_MAGIC */
    uint32_t cmd;           /* TwoyiAudioCmd */
    uint32_t sample_rate;   /* 44100, 48000, etc. */
    uint32_t channels;      /* 1 = Mono, 2 = Stereo */
    uint32_t format;        /* 16 = PCM 16-bit */
    uint32_t payload_len;   /* Number of PCM bytes following */
};

/* Microphone HAL commands */
enum TwoyiMicCmd {
    TWOYI_MIC_CMD_START = 1,
    TWOYI_MIC_CMD_READ_REQ = 2,
    TWOYI_MIC_CMD_DATA = 3,
    TWOYI_MIC_CMD_STOP = 4
};

/* Microphone packet header */
struct TwoyiMicHeader {
    uint32_t magic;         /* TWOYI_MIC_MAGIC */
    uint32_t cmd;           /* TwoyiMicCmd */
    uint32_t sample_rate;   /* 16000, 44100, etc. */
    uint32_t channels;      /* 1 = Mono, 2 = Stereo */
    uint32_t payload_len;   /* Length of PCM data payload */
};

/* Camera HAL commands */
enum TwoyiCameraCmd {
    TWOYI_CAM_CMD_GET_INFO = 1,
    TWOYI_CAM_CMD_INFO_RESP = 2,
    TWOYI_CAM_CMD_START_STREAM = 3,
    TWOYI_CAM_CMD_FRAME = 4,
    TWOYI_CAM_CMD_STOP_STREAM = 5
};

/* Camera frame pixel format */
enum TwoyiCameraFormat {
    TWOYI_CAM_FMT_YUV420 = 1,
    TWOYI_CAM_FMT_NV21 = 2,
    TWOYI_CAM_FMT_JPEG = 3,
    TWOYI_CAM_FMT_RGBA = 4
};

/* Camera packet header */
struct TwoyiCameraHeader {
    uint32_t magic;         /* TWOYI_CAMERA_MAGIC */
    uint32_t cmd;           /* TwoyiCameraCmd */
    uint32_t facing;        /* 0 = Back, 1 = Front */
    uint32_t width;         /* Frame width in pixels */
    uint32_t height;        /* Frame height in pixels */
    uint32_t format;        /* TwoyiCameraFormat */
    uint64_t timestamp_ns;  /* Capture timestamp in nanoseconds */
    uint32_t payload_len;   /* Number of frame bytes following */
};

#ifdef __cplusplus
}
#endif

#endif /* TWOYI_HAL_IPC_H */
