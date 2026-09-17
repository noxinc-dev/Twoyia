/*
 * Twoyi Virtualization Platform - Android Camera HAL Module Header
 * Connects Guest OS CameraService to Host Android Camera2 API via UNIX domain socket.
 */

#ifndef TWOYI_CAMERA_HAL_H
#define TWOYI_CAMERA_HAL_H

#include <hardware/hardware.h>
#include <hardware/camera.h>
#include "twoyi_hal_ipc.h"

#define TWOYI_CAMERA_HARDWARE_MODULE_ID "camera.twoyi"

struct twoyi_camera_device {
    camera_device_t hw_device;
    int camera_id;
    int sock_fd;
    bool streaming;
    uint32_t width;
    uint32_t height;
    camera_notify_callback notify_cb;
    camera_data_callback data_cb;
    camera_data_timestamp_callback data_cb_timestamp;
    void *user_data;
};

#endif /* TWOYI_CAMERA_HAL_H */
