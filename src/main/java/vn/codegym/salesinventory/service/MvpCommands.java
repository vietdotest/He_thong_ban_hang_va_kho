package vn.codegym.salesinventory.service;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.CommandConflictException;

/** Small transaction helpers; never opens a connection or commits its caller. */
public final class MvpCommands {
    private MvpCommands() { }
    public record Ticket(long actor,String operation,String key,Long replayId) { }
    public static Ticket begin(Connection c,long actor,String operation,String key,String input) throws SQLException {
        try { key=UUID.fromString(key).toString(); } catch(Exception e) { throw new IllegalArgumentException("Mã chống ghi lặp không hợp lệ."); }
        String hash;
        try { hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        Sql.update(c,"INSERT IGNORE INTO mvp_commands(actor_id,operation,request_key,input_hash) VALUES(?,?,?,?)",actor,operation,key,hash);
        var row=Sql.one(c,"SELECT input_hash,object_id FROM mvp_commands WHERE actor_id=? AND operation=? AND request_key=? FOR UPDATE",actor,operation,key);
        if(!hash.equals(row.get("input_hash"))) throw new CommandConflictException("Mã xác nhận đã dùng cho dữ liệu khác. Hãy tải lại chứng từ.");
        return new Ticket(actor,operation,key,row.get("object_id")==null?null:Sql.id(row.get("object_id")));
    }
    public static long finish(Connection c,Ticket ticket,long id) throws SQLException {
        Sql.update(c,"UPDATE mvp_commands SET object_id=? WHERE actor_id=? AND operation=? AND request_key=?",id,ticket.actor,ticket.operation,ticket.key);return id;
    }
    public static void version(Map<String,Object> row,long expected) {
        if(Sql.id(row.get("version"))!=expected) throw new CommandConflictException("Chứng từ đã thay đổi. Hãy tải lại trước khi xác nhận.");
    }
    public static BigDecimal quantity(BigDecimal value) {
        if(value==null||value.signum()<=0||value.stripTrailingZeros().scale()>12||value.precision()-value.scale()>26)
            throw new IllegalArgumentException("Số lượng phải dương, tối đa 12 chữ số thập phân.");
        return value.setScale(12,RoundingMode.UNNECESSARY);
    }
    public static BigDecimal money(BigDecimal value) { return value.setScale(4,RoundingMode.HALF_UP); }
    public static BigDecimal decimal(Object value) { return value==null?BigDecimal.ZERO:((BigDecimal)value); }
    public static BigDecimal share(BigDecimal total,BigDecimal quantity,BigDecimal denominator) {
        if(denominator.signum()<=0) throw new IllegalArgumentException("Số lượng nguồn không hợp lệ.");
        return total.multiply(quantity).divide(denominator,4,RoundingMode.HALF_UP);
    }
    public static String text(String value,int max,String label) {
        String clean=value==null?"":value.trim();if(clean.isEmpty()||clean.length()>max) throw new IllegalArgumentException(label+" không được trống hoặc vượt "+max+" ký tự.");return clean;
    }
    public static Timestamp timestamp(Instant now) { return Timestamp.from(now); }
    public static LocalDate date(Instant now) { return now.atZone(vn.codegym.salesinventory.config.VietnamTime.ZONE).toLocalDate(); }
    public static String nextNumber(Connection c,String kind,LocalDate date) throws SQLException {
        Sql.update(c,"INSERT IGNORE INTO mvp_sequences(kind,document_year,next_value) VALUES(?,?,1)",kind,date.getYear());
        long next=Sql.id(Sql.one(c,"SELECT next_value FROM mvp_sequences WHERE kind=? AND document_year=? FOR UPDATE",kind,date.getYear()).get("next_value"));
        Sql.update(c,"UPDATE mvp_sequences SET next_value=next_value+1 WHERE kind=? AND document_year=?",kind,date.getYear());
        return kind+"-"+date.getYear()+"-"+String.format(Locale.ROOT,"%06d",next);
    }
}
