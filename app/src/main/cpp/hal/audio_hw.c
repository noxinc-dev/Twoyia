/*
 * Twoyi Virtualization Platform - Android Audio & Microphone HAL Module Implementation
 * Bridges Guest OS AudioFlinger to Host Android AudioTrack and AudioRecord via UNIX domain sockets.
 */

#include "audio_hw.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "TwoyiAudioHAL"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Helper: Connect to an abstract UNIX domain socket */
static int connect_abstract_socket(const char *sock_name) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        ALOGE("Failed to create UNIX domain socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0'; /* Abstract namespace */
    strncpy(addr.sun_path + 1, sock_name, sizeof(addr.sun_path) - 2);

    int len = 1 + strlen(sock_name) + sizeof(addr.sun_family);
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        ALOGW("Failed to connect to abstract socket '%s': %s (retrying file path)", sock_name, strerror(errno));
        /* Fallback to filesystem socket under /dev/socket/ */
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        snprintf(addr.sun_path, sizeof(addr.sun_path), "/dev/socket/%s", sock_name);
        if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            ALOGE("Fallback connection to '%s' failed: %s", addr.sun_path, strerror(errno));
            close(fd);
            return -1;
        }
    }

    ALOGI("Connected to socket: %s", sock_name);
    return fd;
}

/* ========================================================================= */
/* Audio Output Stream (Playback) Implementation                             */
/* ========================================================================= */

static uint32_t out_get_sample_rate(const struct audio_stream *stream) {
    const struct twoyi_stream_out *out = (const struct twoyi_stream_out *)stream;
    return out->sample_rate;
}

static size_t out_get_buffer_size(const struct audio_stream *stream) {
    return DEFAULT_OUT_BUFFER_SIZE;
}

static audio_channel_mask_t out_get_channels(const struct audio_stream *stream) {
    const struct twoyi_stream_out *out = (const struct twoyi_stream_out *)stream;
    return out->channel_mask;
}

static audio_format_t out_get_format(const struct audio_stream *stream) {
    const struct twoyi_stream_out *out = (const struct twoyi_stream_out *)stream;
    return out->format;
}

static ssize_t out_write(struct audio_stream_out *stream, const void *buffer, size_t bytes) {
    struct twoyi_stream_out *out = (struct twoyi_stream_out *)stream;
    struct twoyi_audio_device *adev = out->dev;

    if (adev->audio_sock_fd < 0) {
        adev->audio_sock_fd = connect_abstract_socket(TWOYI_AUDIO_SOCK_NAME);
        if (adev->audio_sock_fd < 0) {
            /* If host audio service is not yet connected, drop gracefully */
            usleep((bytes * 1000000LL) / (out->sample_rate * 4));
            return bytes;
        }
    }

    /* Build header */
    struct TwoyiAudioHeader hdr;
    hdr.magic = TWOYI_AUDIO_MAGIC;
    hdr.cmd = TWOYI_AUDIO_CMD_WRITE_DATA;
    hdr.sample_rate = out->sample_rate;
    hdr.channels = (out->channel_mask == AUDIO_CHANNEL_OUT_MONO) ? 1 : 2;
    hdr.format = 16;
    hdr.payload_len = (uint32_t)bytes;

    /* Write header and PCM data to host */
    ssize_t written = write(adev->audio_sock_fd, &hdr, sizeof(hdr));
    if (written <= 0) {
        ALOGW("Lost connection to audio host socket, reconnecting...");
        close(adev->audio_sock_fd);
        adev->audio_sock_fd = -1;
        return bytes;
    }

    ssize_t data_written = write(adev->audio_sock_fd, buffer, bytes);
    if (data_written <= 0) {
        close(adev->audio_sock_fd);
        adev->audio_sock_fd = -1;
    }

    return bytes;
}

static int out_set_volume(struct audio_stream_out *stream, float left, float right) {
    struct twoyi_stream_out *out = (struct twoyi_stream_out *)stream;
    struct twoyi_audio_device *adev = out->dev;
    adev->master_volume = (left + right) / 2.0f;

    if (adev->audio_sock_fd >= 0) {
        struct TwoyiAudioHeader hdr;
        hdr.magic = TWOYI_AUDIO_MAGIC;
        hdr.cmd = TWOYI_AUDIO_CMD_SET_VOLUME;
        hdr.sample_rate = (uint32_t)(adev->master_volume * 100.0f);
        hdr.channels = 0;
        hdr.format = 0;
        hdr.payload_len = 0;
        write(adev->audio_sock_fd, &hdr, sizeof(hdr));
    }
    return 0;
}

/* ========================================================================= */
/* Audio Input Stream (Microphone) Implementation                            */
/* ========================================================================= */

static uint32_t in_get_sample_rate(const struct audio_stream *stream) {
    const struct twoyi_stream_in *in = (const struct twoyi_stream_in *)stream;
    return in->sample_rate;
}

static size_t in_get_buffer_size(const struct audio_stream *stream) {
    return DEFAULT_IN_BUFFER_SIZE;
}

static audio_channel_mask_t in_get_channels(const struct audio_stream *stream) {
    const struct twoyi_stream_in *in = (const struct twoyi_stream_in *)stream;
    return in->channel_mask;
}

static audio_format_t in_get_format(const struct audio_stream *stream) {
    const struct twoyi_stream_in *in = (const struct twoyi_stream_in *)stream;
    return in->format;
}

static ssize_t in_read(struct audio_stream_in *stream, void *buffer, size_t bytes) {
    struct twoyi_stream_in *in = (struct twoyi_stream_in *)stream;
    struct twoyi_audio_device *adev = in->dev;

    if (adev->mic_muted) {
        memset(buffer, 0, bytes);
        usleep((bytes * 1000000LL) / (in->sample_rate * 2));
        return bytes;
    }

    if (adev->mic_sock_fd < 0) {
        adev->mic_sock_fd = connect_abstract_socket(TWOYI_MIC_SOCK_NAME);
        if (adev->mic_sock_fd < 0) {
            memset(buffer, 0, bytes);
            usleep((bytes * 1000000LL) / (in->sample_rate * 2));
            return bytes;
        }

        /* Send start capture command */
        struct TwoyiMicHeader start_hdr;
        start_hdr.magic = TWOYI_MIC_MAGIC;
        start_hdr.cmd = TWOYI_MIC_CMD_START;
        start_hdr.sample_rate = in->sample_rate;
        start_hdr.channels = (in->channel_mask == AUDIO_CHANNEL_IN_MONO) ? 1 : 2;
        start_hdr.payload_len = 0;
        write(adev->mic_sock_fd, &start_hdr, sizeof(start_hdr));
    }

    /* Request mic read */
    struct TwoyiMicHeader req;
    req.magic = TWOYI_MIC_MAGIC;
    req.cmd = TWOYI_MIC_CMD_READ_REQ;
    req.sample_rate = in->sample_rate;
    req.channels = (in->channel_mask == AUDIO_CHANNEL_IN_MONO) ? 1 : 2;
    req.payload_len = (uint32_t)bytes;

    if (write(adev->mic_sock_fd, &req, sizeof(req)) <= 0) {
        close(adev->mic_sock_fd);
        adev->mic_sock_fd = -1;
        memset(buffer, 0, bytes);
        return bytes;
    }

    /* Read response header & PCM bytes */
    struct TwoyiMicHeader resp;
    ssize_t r = read(adev->mic_sock_fd, &resp, sizeof(resp));
    if (r == sizeof(resp) && resp.magic == TWOYI_MIC_MAGIC && resp.payload_len > 0) {
        size_t to_read = resp.payload_len < bytes ? resp.payload_len : bytes;
        ssize_t data_read = read(adev->mic_sock_fd, buffer, to_read);
        if (data_read > 0) {
            return data_read;
        }
    }

    memset(buffer, 0, bytes);
    return bytes;
}

/* ========================================================================= */
/* Device Open / Close & Lifecycle                                           */
/* ========================================================================= */

static int adev_set_master_volume(struct audio_hw_device *dev, float volume) {
    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)dev;
    adev->master_volume = volume;
    return 0;
}

static int adev_set_mic_mute(struct audio_hw_device *dev, bool state) {
    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)dev;
    adev->mic_muted = state;
    return 0;
}

static int adev_get_mic_mute(const struct audio_hw_device *dev, bool *state) {
    const struct twoyi_audio_device *adev = (const struct twoyi_audio_device *)dev;
    *state = adev->mic_muted;
    return 0;
}

static int adev_open_output_stream(struct audio_hw_device *dev,
                                   audio_io_handle_t handle,
                                   audio_devices_t devices,
                                   audio_output_flags_t flags,
                                   struct audio_config *config,
                                   struct audio_stream_out **stream_out,
                                   const char *address) {
    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)dev;
    struct twoyi_stream_out *out = (struct twoyi_stream_out *)calloc(1, sizeof(struct twoyi_stream_out));
    if (!out) return -ENOMEM;

    out->dev = adev;
    out->sample_rate = config->sample_rate ? config->sample_rate : DEFAULT_SAMPLE_RATE;
    out->channel_mask = config->channel_mask ? config->channel_mask : AUDIO_CHANNEL_OUT_STEREO;
    out->format = config->format ? config->format : DEFAULT_AUDIO_FORMAT;

    config->sample_rate = out->sample_rate;
    config->channel_mask = out->channel_mask;
    config->format = out->format;

    out->stream.common.get_sample_rate = out_get_sample_rate;
    out->stream.common.get_buffer_size = out_get_buffer_size;
    out->stream.common.get_channels = out_get_channels;
    out->stream.common.get_format = out_get_format;
    out->stream.write = out_write;
    out->stream.set_volume = out_set_volume;

    *stream_out = &out->stream;
    ALOGI("Opened audio output stream (rate=%u, channels=0x%x)", out->sample_rate, out->channel_mask);
    return 0;
}

static void adev_close_output_stream(struct audio_hw_device *dev, struct audio_stream_out *stream) {
    struct twoyi_stream_out *out = (struct twoyi_stream_out *)stream;
    free(out);
}

static int adev_open_input_stream(struct audio_hw_device *dev,
                                  audio_io_handle_t handle,
                                  audio_devices_t devices,
                                  struct audio_config *config,
                                  struct audio_stream_in **stream_in,
                                  audio_input_flags_t flags,
                                  const char *address,
                                  audio_source_t source) {
    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)dev;
    struct twoyi_stream_in *in = (struct twoyi_stream_in *)calloc(1, sizeof(struct twoyi_stream_in));
    if (!in) return -ENOMEM;

    in->dev = adev;
    in->sample_rate = config->sample_rate ? config->sample_rate : DEFAULT_SAMPLE_RATE;
    in->channel_mask = config->channel_mask ? config->channel_mask : AUDIO_CHANNEL_IN_MONO;
    in->format = config->format ? config->format : DEFAULT_AUDIO_FORMAT;

    config->sample_rate = in->sample_rate;
    config->channel_mask = in->channel_mask;
    config->format = in->format;

    in->stream.common.get_sample_rate = in_get_sample_rate;
    in->stream.common.get_buffer_size = in_get_buffer_size;
    in->stream.common.get_channels = in_get_channels;
    in->stream.common.get_format = in_get_format;
    in->stream.read = in_read;

    *stream_in = &in->stream;
    ALOGI("Opened audio input stream (rate=%u, channels=0x%x)", in->sample_rate, in->channel_mask);
    return 0;
}

static void adev_close_input_stream(struct audio_hw_device *dev, struct audio_stream_in *stream) {
    struct twoyi_stream_in *in = (struct twoyi_stream_in *)stream;
    free(in);
}

static int adev_close(hw_device_t *device) {
    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)device;
    if (adev->audio_sock_fd >= 0) close(adev->audio_sock_fd);
    if (adev->mic_sock_fd >= 0) close(adev->mic_sock_fd);
    free(adev);
    return 0;
}

static int adev_open(const hw_module_t *module, const char *name, hw_device_t **device) {
    if (strcmp(name, AUDIO_HARDWARE_INTERFACE) != 0) return -EINVAL;

    struct twoyi_audio_device *adev = (struct twoyi_audio_device *)calloc(1, sizeof(struct twoyi_audio_device));
    if (!adev) return -ENOMEM;

    adev->hw_device.common.tag = HARDWARE_DEVICE_TAG;
    adev->hw_device.common.version = AUDIO_DEVICE_API_VERSION_2_0;
    adev->hw_device.common.module = (struct hw_module_t *)module;
    adev->hw_device.common.close = adev_close;

    adev->hw_device.set_master_volume = adev_set_master_volume;
    adev->hw_device.set_mic_mute = adev_set_mic_mute;
    adev->hw_device.get_mic_mute = adev_get_mic_mute;
    adev->hw_device.open_output_stream = adev_open_output_stream;
    adev->hw_device.close_output_stream = adev_close_output_stream;
    adev->hw_device.open_input_stream = adev_open_input_stream;
    adev->hw_device.close_input_stream = adev_close_input_stream;

    adev->audio_sock_fd = -1;
    adev->mic_sock_fd = -1;
    adev->master_volume = 1.0f;
    adev->mic_muted = false;

    *device = &adev->hw_device.common;
    ALOGI("Twoyi Audio HAL Initialized successfully");
    return 0;
}

static struct hw_module_methods_t hal_module_methods = {
    .open = adev_open,
};

struct audio_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = AUDIO_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = AUDIO_HARDWARE_MODULE_ID,
        .name = "Twoyi Virtual Audio HAL",
        .author = "Twoyi",
        .methods = &hal_module_methods,
    },
};
