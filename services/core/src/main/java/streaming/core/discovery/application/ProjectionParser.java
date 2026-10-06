package streaming.core.discovery.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import streaming.core.discovery.domain.StreamProjection;
import tools.jackson.databind.JsonNode;

/**
 * Validates Streaming's public snapshot (event envelope and payload, or one item of a rebuild cut) and turns it into
 * a {@link StreamProjection}. Unknown fields are ignored so producers can add compatible fields.
 */
@Component
public class ProjectionParser {
    public static final String EVENT_TYPE="StreamDiscoverySnapshot";
    private static final Pattern ID=Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> STATUSES=Set.of("OFFLINE","PREPARING","LIVE","ENDED");
    private static final Set<String> AVAILABILITIES=Set.of("PLAYABLE","RECONNECTING","OFFLINE");
    /** Far inside PostgreSQL's timestamptz range, so a parsed instant can always be stored. */
    private static final Instant EARLIEST=Instant.parse("1970-01-01T00:00:00Z"), LATEST=Instant.parse("9999-12-31T23:59:59Z");

    public record ParsedEvent(String eventId,String eventHash,StreamProjection projection) { }

    public ParsedEvent parseEvent(JsonNode body) {
        if(body==null || !body.isObject()) throw invalid("body","OBJECT_REQUIRED");
        String eventId=text(body,"eventId");
        if(!ID.matcher(eventId).matches()) throw invalid("eventId","INVALID_ID");
        if(!EVENT_TYPE.equals(text(body,"eventType"))) throw invalid("eventType","UNSUPPORTED_TYPE");
        if(longValue(body,"schemaVersion",1)!=1) throw invalid("schemaVersion","UNSUPPORTED_VERSION");
        if(!"streaming".equals(text(body,"producer"))) throw invalid("producer","UNSUPPORTED_PRODUCER");
        instant(body,"occurredAtUtc",false);
        JsonNode payload=body.get("payload");
        if(payload==null || !payload.isObject()) throw invalid("payload","OBJECT_REQUIRED");
        StreamProjection projection=parsePayload(payload);
        if(!("stream:"+projection.streamId()).equals(text(body,"aggregateId"))) throw invalid("aggregateId","MISMATCH");
        if(longValue(body,"sequence",1)!=projection.projectionVersion()) throw invalid("sequence","MISMATCH");
        return new ParsedEvent(eventId,CanonicalJson.hash(body),projection);
    }

    public StreamProjection parsePayload(JsonNode p) {
        if(p==null || !p.isObject()) throw invalid("payload","OBJECT_REQUIRED");
        String streamId=text(p,"streamId"), channelId=text(p,"channelId");
        if(!ID.matcher(streamId).matches()) throw invalid("streamId","INVALID_ID");
        if(!ID.matcher(channelId).matches()) throw invalid("channelId","INVALID_ID");
        long projectionVersion=longValue(p,"projectionVersion",1), position=longValue(p,"discoveryPosition",1);
        long metadataVersion=longValue(p,"metadataVersion",1), generation=longValue(p,"streamGeneration",0), countVersion=longValue(p,"countVersion",0);
        String title=text(p,"title");
        if(title.isBlank() || title.codePointCount(0,title.length())>200) throw invalid("title","INVALID_TITLE");
        JsonNode category=object(p,"category");
        String categoryId=text(category,"id"), categoryName=text(category,"name");
        if(!ID.matcher(categoryId).matches() || categoryName.isBlank()) throw invalid("category","INVALID_VALUE");
        List<StreamProjection.Tag> tags=tags(p.get("tags"));
        String status=text(p,"status"), availability=text(p,"availability");
        if(!STATUSES.contains(status)) throw invalid("status","UNSUPPORTED_VALUE");
        if(!AVAILABILITIES.contains(availability)) throw invalid("availability","UNSUPPORTED_VALUE");
        String sessionId=optionalText(p,"sessionId");
        if(sessionId!=null && !ID.matcher(sessionId).matches()) throw invalid("sessionId","INVALID_ID");
        Long sessionVersion=isNull(p,"sessionVersion")?null:longValue(p,"sessionVersion",1);
        Instant startedAt=instant(p,"startedAtUtc",true);
        Instant stateObserved=instant(p,"stateObservedAtUtc",false);
        Instant countObserved=instant(p,"viewerCountObservedAtUtc",true);
        long viewers=longValue(p,"viewerCount",0);
        if(viewers>Integer.MAX_VALUE) throw invalid("viewerCount","OUT_OF_RANGE");
        // A stream that can be (or is about to be) watched always has a live session.
        if(!"OFFLINE".equals(availability) && (!"LIVE".equals(status) || sessionId==null || sessionVersion==null || startedAt==null))
            throw invalid("availability","INCONSISTENT_STATE");
        return new StreamProjection(streamId,channelId,projectionVersion,position,metadataVersion,title.strip(),categoryId,categoryName.strip(),
                tags,sessionId,generation,sessionVersion,status,availability,startedAt,stateObserved,(int)viewers,countVersion,countObserved,
                CanonicalJson.hash(p));
    }

    private List<StreamProjection.Tag> tags(JsonNode node) {
        if(node==null || !node.isArray() || node.size()>5) throw invalid("tags","INVALID_TAGS");
        var tags=new ArrayList<StreamProjection.Tag>();
        var seen=new HashSet<String>();
        for(JsonNode tag:node) {
            if(!tag.isObject()) throw invalid("tags","INVALID_TAGS");
            String id=text(tag,"id"), name=text(tag,"name");
            if(!ID.matcher(id).matches() || name.isBlank() || !seen.add(id)) throw invalid("tags","INVALID_TAGS");
            tags.add(new StreamProjection.Tag(id,name.strip()));
        }
        return tags;
    }

    private static JsonNode object(JsonNode parent,String field) {
        JsonNode value=parent.get(field);
        if(value==null || !value.isObject()) throw invalid(field,"OBJECT_REQUIRED");
        return value;
    }
    private static boolean isNull(JsonNode parent,String field) { JsonNode v=parent.get(field); return v==null || v.isNull(); }
    private static String text(JsonNode parent,String field) {
        JsonNode value=parent.get(field);
        if(value==null || !value.isTextual()) throw invalid(field,"TEXT_REQUIRED");
        // PostgreSQL text cannot hold NUL; reject it here so a malformed event is a 422, not a retried server error.
        if(value.textValue().indexOf('\0')>=0) throw invalid(field,"INVALID_TEXT");
        return value.textValue();
    }
    private static String optionalText(JsonNode parent,String field) {
        if(isNull(parent,field)) return null;
        return text(parent,field);
    }
    private static long longValue(JsonNode parent,String field,long min) {
        JsonNode value=parent.get(field);
        if(value==null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid(field,"INTEGER_REQUIRED");
        long number=value.longValue();
        if(number<min) throw invalid(field,"OUT_OF_RANGE");
        return number;
    }
    private static Instant instant(JsonNode parent,String field,boolean nullable) {
        if(isNull(parent,field)) { if(nullable) return null; throw invalid(field,"TIMESTAMP_REQUIRED"); }
        Instant value;
        try { value=OffsetDateTime.parse(text(parent,field)).toInstant().truncatedTo(ChronoUnit.MICROS); }
        catch(DateTimeParseException e) { throw invalid(field,"INVALID_TIMESTAMP"); }
        if(value.isBefore(EARLIEST) || value.isAfter(LATEST)) throw invalid(field,"INVALID_TIMESTAMP");
        return value;
    }
    private static DiscoveryException invalid(String field,String reason) {
        return new DiscoveryException(HttpStatus.UNPROCESSABLE_ENTITY,"INVALID_EVENT","El snapshot no cumple el contrato.",Map.of(field,reason));
    }
}
