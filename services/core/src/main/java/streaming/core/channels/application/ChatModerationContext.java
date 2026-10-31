package streaming.core.channels.application;

import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Channels decides ownership for Chat moderation; Chat keeps blocks, removals and their storage. */
@Service
public class ChatModerationContext {
    private final AccountAuthentication accounts;
    private final ChannelStore channels;
    public ChatModerationContext(AccountAuthentication accounts,ChannelStore channels) { this.accounts=accounts; this.channels=channels; }

    public Context authorize(String credential,JsonNode request) {
        var principal=accounts.introspect(credential).orElseThrow(()->new ChannelException(
                HttpStatus.UNAUTHORIZED,"SESSION_INVALID","La sesión no está vigente."));
        if(request==null || !request.isObject() || request.size()!=1 || !request.path("channelId").isTextual()
                || !request.get("channelId").asText().matches("[A-Za-z0-9_-]{1,64}"))
            throw new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","La solicitud no cumple el contrato.");
        var channel=channels.find(request.get("channelId").asText()).orElseThrow(()->new ChannelException(
                HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","El canal no existe."));
        if(!channel.ownerUserId().equals(principal.userId())) throw new ChannelException(
                HttpStatus.FORBIDDEN,"CHANNEL_FORBIDDEN","Solo el propietario puede moderar el chat del canal.");
        return new Context(principal.userId(),channel.channelId(),Instant.now());
    }

    public record Context(String userId,String channelId,Instant authorizedAtUtc) { }
}
