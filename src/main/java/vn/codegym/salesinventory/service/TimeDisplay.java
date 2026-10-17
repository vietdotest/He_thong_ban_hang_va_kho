package vn.codegym.salesinventory.service;

import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import vn.codegym.salesinventory.config.VietnamTime;

public final class TimeDisplay {
    private static final DateTimeFormatter FORMAT=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");
    private TimeDisplay() { }
    public static String format(Object value) {
        if(value==null)return "";
        if(value instanceof Timestamp stamp)return FORMAT.format(stamp.toInstant().atZone(VietnamTime.ZONE));
        if(value instanceof Instant instant)return FORMAT.format(instant.atZone(VietnamTime.ZONE));
        if(value instanceof LocalDateTime local)return FORMAT.format(local);
        if(value instanceof OffsetDateTime offset)return FORMAT.format(offset.atZoneSameInstant(VietnamTime.ZONE));
        return value.toString();
    }
}
