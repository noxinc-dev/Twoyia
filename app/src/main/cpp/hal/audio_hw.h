/*
 * Twoyi Virtualization Platform - Android Audio & Microphone HAL Module
 * Implements Android Audio HAL (audio_hw_device_t) connecting to Host Twoyi Audio/Mic server.
 */

#ifndef TWOYI_AUDIO_HW_H
#define TWOYI_AUDIO_HW_H

#include <hardware/hardware.h>
#include <hardware/audio.h>
#include "twoyi_hal_ipc.h"

#define TWOYI_AUDIO_HARDWARE_MODULE_ID "audio.primary.twoyi"

/* Default audio streaming parameters */
#define DEFAULT_SAMPLE_RATE     44100
#define DEFAULT_CHANNEL_COUNT   2
#define DEFAULT_AUDIO_FORMAT    AUDIO_FORMAT_PCM_16_BIT
#define DEFAULT_OUT_BUFFER_SIZE 4096
#define DEFAULT_IN_BUFFER_SIZE  4096

struct twoyi_audio_device {
    struct audio_hw_device hw_device;
    int audio_sock_fd;
    int mic_sock_fd;
    float master_volume;
    bool mic_muted;
};

struct twoyi_stream_out {
    struct audio_stream_out stream;
    struct twoyi_audio_device *dev;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
};

struct twoyi_stream_in {
    struct audio_stream_in stream;
    struct twoyi_audio_device *dev;
    uint32_t sample_rate;
    audio_channel_mask_t channel_mask;
    audio_format_t format;
};

#endif /* TWOYI_AUDIO_HW_H */
