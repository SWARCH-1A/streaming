package streaming.core.discovery.domain;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the client address used as the rate-limit key. By default (no trusted proxies) it is the socket address.
 * Only when the direct peer is a configured proxy is {@code X-Forwarded-For} read, from the right, skipping other
 * trusted proxies; the first untrusted entry is the client. A malformed entry stops the walk and the peer is used,
 * so a forged header can never select an arbitrary address.
 */
public final class TrustedProxies {
    private static final TrustedProxies NONE=new TrustedProxies(List.of());
    private final List<Cidr> trusted;

    private TrustedProxies(List<Cidr> trusted) { this.trusted=List.copyOf(trusted); }

    /** @param csv comma-separated addresses or CIDR blocks; blank means none. @throws IllegalArgumentException if malformed. */
    public static TrustedProxies parse(String csv) {
        if(csv==null || csv.isBlank()) return NONE;
        var cidrs=new ArrayList<Cidr>();
        for(String entry:csv.split(",")) {
            String value=entry.strip();
            if(value.isEmpty()) continue;
            int slash=value.indexOf('/');
            byte[] network=literal(slash<0?value:value.substring(0,slash));
            if(network==null) throw new IllegalArgumentException("Invalid trusted proxy: "+value);
            int prefix=network.length*8;
            if(slash>=0) {
                String bits=value.substring(slash+1);
                if(!bits.matches("[0-9]{1,3}") || Integer.parseInt(bits)>network.length*8) throw new IllegalArgumentException("Invalid trusted proxy prefix: "+value);
                prefix=Integer.parseInt(bits);
            }
            cidrs.add(new Cidr(network,prefix));
        }
        return cidrs.isEmpty()?NONE:new TrustedProxies(cidrs);
    }

    public boolean isEmpty() { return trusted.isEmpty(); }

    public String clientAddress(String remoteAddress,List<String> forwardedFor) {
        byte[] peer=literal(remoteAddress);
        if(peer==null) return remoteAddress==null?"unknown":remoteAddress;
        String peerText=text(peer);
        if(trusted.isEmpty() || !isTrusted(peer) || forwardedFor==null || forwardedFor.isEmpty()) return peerText;
        var chain=new ArrayList<String>();
        for(String header:forwardedFor) if(header!=null) for(String part:header.split(",",-1)) chain.add(part.strip());
        for(int i=chain.size()-1;i>=0;i--) {
            byte[] address=literal(chain.get(i));
            if(address==null) return peerText;
            if(!isTrusted(address)) return text(address);
        }
        return peerText;
    }

    private boolean isTrusted(byte[] address) {
        for(Cidr cidr:trusted) if(cidr.contains(address)) return true;
        return false;
    }

    private static byte[] literal(String value) {
        if(value==null || value.isEmpty() || value.length()>45) return null;
        try { return InetAddress.ofLiteral(value).getAddress(); }
        catch(IllegalArgumentException e) { return null; }
    }

    private static String text(byte[] address) {
        try { return InetAddress.getByAddress(address).getHostAddress(); }
        catch(java.net.UnknownHostException e) { throw new IllegalStateException(e); }
    }

    private record Cidr(byte[] network,int prefix) {
        boolean contains(byte[] address) {
            if(address.length!=network.length) return false;
            int whole=prefix/8, rest=prefix%8;
            for(int i=0;i<whole;i++) if(address[i]!=network[i]) return false;
            if(rest==0) return true;
            int mask=0xFF<<(8-rest);
            return (address[whole]&mask)==(network[whole]&mask);
        }
    }
}
