package streaming.core.discovery.application;

import java.util.TreeSet;
import streaming.core.discovery.domain.FilterHash;
import tools.jackson.databind.JsonNode;

/** Key-order independent serialization, so equal content always hashes equally. */
final class CanonicalJson {
    private CanonicalJson() { }

    static String hash(JsonNode node) { return FilterHash.sha256(write(node)); }

    static String write(JsonNode node) {
        var out=new StringBuilder();
        append(out,node);
        return out.toString();
    }

    private static void append(StringBuilder out,JsonNode node) {
        if(node==null || node.isNull() || node.isMissingNode()) out.append("null");
        else if(node.isObject()) {
            out.append('{');
            boolean first=true;
            for(String name:new TreeSet<>(node.propertyNames())) {
                if(!first) out.append(',');
                first=false;
                quote(out,name); out.append(':'); append(out,node.get(name));
            }
            out.append('}');
        } else if(node.isArray()) {
            out.append('[');
            boolean first=true;
            for(JsonNode element:node) { if(!first) out.append(','); first=false; append(out,element); }
            out.append(']');
        } else if(node.isTextual()) quote(out,node.textValue());
        else if(node.isBoolean()) out.append(node.booleanValue());
        else if(node.isIntegralNumber()) out.append(node.bigIntegerValue());
        else out.append(node.decimalValue().stripTrailingZeros().toPlainString());
    }

    private static void quote(StringBuilder out,String value) {
        out.append('"');
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            switch(c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> { if(c<0x20) out.append(String.format("\\u%04x",(int)c)); else out.append(c); }
            }
        }
        out.append('"');
    }
}
