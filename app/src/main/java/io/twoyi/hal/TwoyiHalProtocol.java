/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.hal;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Protocol specifications for Twoyi Hardware Abstraction Layer (HAL).
 * Handles binary frame and packet parsing between Guest HAL and Host Services.
 */
public final class TwoyiHalProtocol {

    private TwoyiHalProtocol() {}

    /* Abstract UNIX domain socket names */
    public static final String SOCK_AUDIO = "TWOYI_AUDIO_SOCK";
    public static final String SOCK_MIC = "TWOYI_MIC_SOCK";
    public static final String SOCK_CAMERA = "TWOYI_CAMERA_SOCK";

    /* Packet magic signatures */
    public static final int AUDIO_MAGIC = 0x54574155; // "TWAU"
    public static final int MIC_MAGIC = 0x54574D43;   // "TWMC"
    public static final int CAMERA_MAGIC = 0x5457434D;// "TWCM"

    /* Audio HAL commands */
    public static final int CMD_AUDIO_INIT = 1;
    public static final int CMD_AUDIO_WRITE_DATA = 2;
    public static final int CMD_AUDIO_SET_VOLUME = 3;
    public static final int CMD_AUDIO_PAUSE = 4;
    public static final int CMD_AUDIO_RESUME = 5;
    public static final int CMD_AUDIO_CLOSE = 6;

    /* Microphone HAL commands */
    public static final int CMD_MIC_START = 1;
    public static final int CMD_MIC_READ_REQ = 2;
    public static final int CMD_MIC_DATA = 3;
    public static final int CMD_MIC_STOP = 4;

    /* Camera HAL commands */
    public static final int CMD_CAM_GET_INFO = 1;
    public static final int CMD_CAM_INFO_RESP = 2;
    public static final int CMD_CAM_START_STREAM = 3;
    public static final int CMD_CAM_FRAME = 4;
    public static final int CMD_CAM_STOP_STREAM = 5;

    /* Camera pixel formats */
    public static final int CAM_FMT_YUV420 = 1;
    public static final int CAM_FMT_NV21 = 2;
    public static final int CAM_FMT_JPEG = 3;
    public static final int CAM_FMT_RGBA = 4;

    /**
     * Read a little-endian 32-bit integer from stream.
     */
    public static int readIntLE(DataInputStream dis) throws IOException {
        int b1 = dis.readUnsignedByte();
        int b2 = dis.readUnsignedByte();
        int b3 = dis.readUnsignedByte();
        int b4 = dis.readUnsignedByte();
        return (b1) | (b2 << 8) | (b3 << 16) | (b4 << 24);
    }

    /**
     * Write a little-endian 32-bit integer to stream.
     */
    public static void writeIntLE(DataOutputStream dos, int val) throws IOException {
        dos.writeByte(val & 0xFF);
        dos.writeByte((val >> 8) & 0xFF);
        dos.writeByte((val >> 16) & 0xFF);
        dos.writeByte((val >> 24) & 0xFF);
    }

    /**
     * Write a little-endian 64-bit integer to stream.
     */
    public static void writeLongLE(DataOutputStream dos, long val) throws IOException {
        for (int i = 0; i < 8; i++) {
            dos.writeByte((int) ((val >> (i * 8)) & 0xFF));
        }
    }
}
