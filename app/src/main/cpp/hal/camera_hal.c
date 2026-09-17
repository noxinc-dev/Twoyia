/*
 * Twoyi Virtualization Platform - Android Camera HAL Module Implementation
 * Streams camera frames from Host Camera2 service to Guest OS Camera applications.
 */

#include "camera_hal.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>

#define LOG_TAG "TwoyiCameraHAL"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int connect_camera_socket(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        ALOGE("Failed to create camera UNIX socket: %s", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = '\0';
    strncpy(addr.sun_path + 1, TWOYI_CAMERA_SOCK_NAME, sizeof(addr.sun_path) - 2);

    int len = 1 + strlen(TWOYI_CAMERA_SOCK_NAME) + sizeof(addr.sun_family);
    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        /* Fallback to filesystem socket */
        memset(&addr, 0, sizeof(addr));
        addr.sun_family = AF_UNIX;
        snprintf(addr.sun_path, sizeof(addr.sun_path), "/dev/socket/%s", TWOYI_CAMERA_SOCK_NAME);
        if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
            ALOGE("Failed to connect to camera host socket: %s", strerror(errno));
            close(fd);
            return -1;
        }
    }

    ALOGI("Connected to camera host socket");
    return fd;
}

static void *camera_frame_worker(void *arg) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)arg;
    uint8_t *frame_buffer = (uint8_t *)malloc(1920 * 1080 * 3 / 2); /* Max YUV420 buffer */

    ALOGI("Camera frame listener started for camera %d", cam->camera_id);

    while (cam->streaming && cam->sock_fd >= 0) {
        struct TwoyiCameraHeader hdr;
        ssize_t r = read(cam->sock_fd, &hdr, sizeof(hdr));
        if (r <= 0) {
            ALOGW("Camera host connection closed or error");
            break;
        }

        if (hdr.magic != TWOYI_CAMERA_MAGIC) {
            ALOGW("Invalid camera packet magic: 0x%x", hdr.magic);
            continue;
        }

        if (hdr.cmd == TWOYI_CAM_CMD_FRAME && hdr.payload_len > 0) {
            size_t total_read = 0;
            while (total_read < hdr.payload_len) {
                ssize_t chunk = read(cam->sock_fd, frame_buffer + total_read, hdr.payload_len - total_read);
                if (chunk <= 0) break;
                total_read += chunk;
            }

            if (total_read == hdr.payload_len && cam->data_cb_timestamp) {
                /* Deliver frame timestamp callback to CameraService */
                cam->data_cb_timestamp((nsecs_t)hdr.timestamp_ns, CAMERA_MSG_VIDEO_FRAME, NULL, 0, cam->user_data);
            }
        }
    }

    free(frame_buffer);
    ALOGI("Camera frame listener terminated");
    return NULL;
}

static int twoyi_set_preview_window(struct camera_device *device, struct preview_stream_ops *window) {
    return 0;
}

static void twoyi_set_callbacks(struct camera_device *device,
                               camera_notify_callback notify_cb,
                               camera_data_callback data_cb,
                               camera_data_timestamp_callback data_cb_timestamp,
                               camera_request_memory get_memory,
                               void *user) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)device;
    cam->notify_cb = notify_cb;
    cam->data_cb = data_cb;
    cam->data_cb_timestamp = data_cb_timestamp;
    cam->user_data = user;
}

static int twoyi_start_preview(struct camera_device *device) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)device;
    if (cam->streaming) return 0;

    if (cam->sock_fd < 0) {
        cam->sock_fd = connect_camera_socket();
        if (cam->sock_fd < 0) return -EIO;
    }

    /* Send start stream command to host */
    struct TwoyiCameraHeader hdr;
    memset(&hdr, 0, sizeof(hdr));
    hdr.magic = TWOYI_CAMERA_MAGIC;
    hdr.cmd = TWOYI_CAM_CMD_START_STREAM;
    hdr.facing = (uint32_t)cam->camera_id;
    hdr.width = cam->width ? cam->width : 1280;
    hdr.height = cam->height ? cam->height : 720;
    hdr.format = TWOYI_CAM_FMT_YUV420;

    write(cam->sock_fd, &hdr, sizeof(hdr));
    cam->streaming = true;

    pthread_t thread;
    pthread_create(&thread, NULL, camera_frame_worker, cam);
    pthread_detach(thread);

    ALOGI("Camera preview started (facing=%d, %ux%u)", cam->camera_id, hdr.width, hdr.height);
    return 0;
}

static void twoyi_stop_preview(struct camera_device *device) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)device;
    if (!cam->streaming) return;

    cam->streaming = false;
    if (cam->sock_fd >= 0) {
        struct TwoyiCameraHeader hdr;
        memset(&hdr, 0, sizeof(hdr));
        hdr.magic = TWOYI_CAMERA_MAGIC;
        hdr.cmd = TWOYI_CAM_CMD_STOP_STREAM;
        write(cam->sock_fd, &hdr, sizeof(hdr));
        close(cam->sock_fd);
        cam->sock_fd = -1;
    }
    ALOGI("Camera preview stopped");
}

static int twoyi_preview_enabled(struct camera_device *device) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)device;
    return cam->streaming ? 1 : 0;
}

static int twoyi_camera_close(hw_device_t *device) {
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)device;
    twoyi_stop_preview(&cam->hw_device);
    free(cam);
    return 0;
}

static int twoyi_camera_device_open(const struct hw_module_t *module, const char *id, struct hw_device_t **device) {
    int camera_id = atoi(id);
    struct twoyi_camera_device *cam = (struct twoyi_camera_device *)calloc(1, sizeof(struct twoyi_camera_device));
    if (!cam) return -ENOMEM;

    cam->hw_device.common.tag = HARDWARE_DEVICE_TAG;
    cam->hw_device.common.version = HARDWARE_DEVICE_API_VERSION(1, 0);
    cam->hw_device.common.module = (struct hw_module_t *)module;
    cam->hw_device.common.close = twoyi_camera_close;

    cam->hw_device.ops = (struct camera_device_ops *)calloc(1, sizeof(struct camera_device_ops));
    cam->hw_device.ops->set_preview_window = twoyi_set_preview_window;
    cam->hw_device.ops->set_callbacks = twoyi_set_callbacks;
    cam->hw_device.ops->start_preview = twoyi_start_preview;
    cam->hw_device.ops->stop_preview = twoyi_stop_preview;
    cam->hw_device.ops->preview_enabled = twoyi_preview_enabled;

    cam->camera_id = camera_id;
    cam->sock_fd = -1;
    cam->streaming = false;
    cam->width = 1280;
    cam->height = 720;

    *device = &cam->hw_device.common;
    ALOGI("Opened Twoyi Camera HAL device (id=%d)", camera_id);
    return 0;
}

static int twoyi_get_number_of_cameras(void) {
    return 2; /* 0: Back camera, 1: Front camera */
}

static int twoyi_get_camera_info(int camera_id, struct camera_info *info) {
    if (camera_id == 0) {
        info->facing = CAMERA_FACING_BACK;
        info->orientation = 90;
    } else {
        info->facing = CAMERA_FACING_FRONT;
        info->orientation = 270;
    }
    return 0;
}

static struct hw_module_methods_t camera_module_methods = {
    .open = twoyi_camera_device_open,
};

struct camera_module HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = CAMERA_MODULE_API_VERSION_2_4,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = TWOYI_CAMERA_HARDWARE_MODULE_ID,
        .name = "Twoyi Virtual Camera HAL",
        .author = "Twoyi",
        .methods = &camera_module_methods,
    },
    .get_number_of_cameras = twoyi_get_number_of_cameras,
    .get_camera_info = twoyi_get_camera_info,
};
