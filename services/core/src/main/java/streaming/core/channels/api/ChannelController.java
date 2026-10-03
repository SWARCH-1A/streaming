package streaming.core.channels.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import streaming.core.channels.application.ChannelException;
import streaming.core.accounts.identity.domain.IdentityRules;
import streaming.core.channels.application.ChannelQueries;

@RestController
public class ChannelController {
    private final ChannelQueries channels;
    public ChannelController(ChannelQueries channels) { this.channels=channels; }

    @GetMapping("/api/channels/by-handle/{handle}")
    public ChannelQueries.Bootstrap byHandle(@PathVariable String handle) {
        String canonical;
        try { canonical=IdentityRules.canonicalHandle(handle); }
        catch(IllegalArgumentException e) { throw new ChannelException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR",e.getMessage()); }
        return channels.byHandle(canonical).orElseThrow(ChannelController::notFound);
    }

    @GetMapping("/api/channels/by-owner/{userId}")
    public ChannelQueries.Bootstrap byOwner(@PathVariable String userId) {
        return channels.byOwner(userId).orElseThrow(ChannelController::notFound);
    }

    private static ChannelException notFound() {
        return new ChannelException(HttpStatus.NOT_FOUND,"CHANNEL_NOT_FOUND","Canal no encontrado.");
    }
}
