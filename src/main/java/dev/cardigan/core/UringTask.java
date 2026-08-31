// SPDX-License-Identifier: MPL-2.0

package dev.cardigan.core;

public class UringTask {
    public final int id;
    public int generation;
    public long userData;
    public Thread thread;
    public UringEventLoop.CompletionHandler completionHandler;
    public int result;
    public int flags;
    public int vectorSlot = -1;
    public int vectorIndex;
    public int vectorCount;
    public int vectorRemaining;

    // Operation payload
    public byte opcode;
    public byte opFlags;
    public int fd;
    public int rawFd;
    public long off;
    public long addr;
    public int len;
    public int unionFlags;
    public short bufGroup;

    public UringTask(int id) {
        this.id = id;
    }
}
