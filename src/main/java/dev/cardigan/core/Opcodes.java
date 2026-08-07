// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

public class Opcodes {
    public static final byte IORING_OP_NOP = 0;
    public static final byte IORING_OP_POLL_ADD = 6;
    public static final byte IORING_OP_SENDMSG = 9;
    public static final byte IORING_OP_RECVMSG = 10;
    public static final byte IORING_OP_ACCEPT = 13;
    public static final byte IORING_OP_ASYNC_CANCEL = 14;
    public static final byte IORING_OP_CLOSE = 19;
    public static final byte IORING_OP_READ = 22;
    public static final byte IORING_OP_WRITE = 23;
    public static final byte IORING_OP_SEND = 26;
    public static final byte IORING_OP_RECV = 27;
    public static final byte IORING_OP_PROVIDE_BUFFERS = 31;
    public static final byte IORING_OP_REMOVE_BUFFERS = 32;
    public static final byte IORING_OP_SEND_ZC = 47;

    public static final byte IOSQE_FIXED_FILE = (byte) 0x01;
    public static final byte IOSQE_BUFFER_SELECT = (byte) 0x20;

    public static final int IORING_CQE_F_BUFFER = 1;
    public static final int IORING_CQE_F_MORE = 1 << 1;
    public static final int IORING_CQE_BUFFER_SHIFT = 16;

    public static final int IORING_ASYNC_CANCEL_ALL = 1 << 0;
    public static final int IORING_ASYNC_CANCEL_FD = 1 << 1;

    public static final short IORING_ACCEPT_MULTISHOT = 1 << 0;
    public static final short IORING_RECV_MULTISHOT = 1 << 1;

    public static final int IORING_SETUP_SINGLE_ISSUER = 1 << 12;
    public static final int IORING_SETUP_DEFER_TASKRUN = 1 << 13;
}
