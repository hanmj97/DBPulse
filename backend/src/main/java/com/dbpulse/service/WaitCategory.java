package com.dbpulse.service;

import java.util.Locale;

/** Conservative labels for SQL Server wait types; labels do not assert root cause. */
public final class WaitCategory {
    private WaitCategory() {}

    public static String classify(String waitType) {
        if (waitType == null || waitType.isBlank()) return "OTHER";
        String type = waitType.toUpperCase(Locale.ROOT);
        if (type.startsWith("LCK_M_")) return "LOCK";
        if (type.startsWith("PAGEIOLATCH_") || type.startsWith("IO_COMPLETION")
            || type.startsWith("ASYNC_IO_COMPLETION") || type.startsWith("IO_RETRY")
            || type.startsWith("BACKUPIO")) return "STORAGE_IO";
        if (type.startsWith("WRITELOG") || type.startsWith("LOGBUFFER")
            || type.startsWith("LOGMGR")) return "TRANSACTION_LOG";
        if (type.startsWith("RESOURCE_SEMAPHORE") || type.startsWith("MEMORY_ALLOCATION")
            || type.startsWith("CMEMTHREAD")) return "MEMORY";
        if (type.startsWith("CXPACKET") || type.startsWith("CXCONSUMER")
            || type.startsWith("CXSYNC_") || type.startsWith("EXCHANGE")) return "PARALLELISM";
        if (type.startsWith("SOS_SCHEDULER_YIELD") || type.startsWith("THREADPOOL")) return "SCHEDULER";
        if (type.startsWith("ASYNC_NETWORK_IO") || type.startsWith("NET_WAITFOR_PACKET")) return "NETWORK";
        if (type.startsWith("HADR_")) return "AVAILABILITY";
        return "OTHER";
    }
}
