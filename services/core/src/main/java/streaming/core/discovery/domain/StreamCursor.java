package streaming.core.discovery.domain;

import java.util.UUID;

/** Opaque position inside a materialized ranking snapshot: {@code <snapshotId>.<offset>}. */
public record StreamCursor(UUID snapshotId,int offset) {
    public String format() { return snapshotId+"."+offset; }

    /** @throws IllegalArgumentException when the value is not a cursor produced by {@link #format()}. */
    public static StreamCursor parse(String value) {
        if(value==null || value.length()>64) throw new IllegalArgumentException("cursor");
        int dot=value.indexOf('.');
        if(dot!=36) throw new IllegalArgumentException("cursor");
        String id=value.substring(0,dot), offset=value.substring(dot+1);
        if(!offset.matches("[0-9]{1,6}")) throw new IllegalArgumentException("cursor");
        UUID uuid;
        try { uuid=UUID.fromString(id); } catch(IllegalArgumentException e) { throw new IllegalArgumentException("cursor"); }
        if(!uuid.toString().equals(id)) throw new IllegalArgumentException("cursor");
        return new StreamCursor(uuid,Integer.parseInt(offset));
    }
}
