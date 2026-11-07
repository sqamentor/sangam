// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.modules.channels;

import java.util.List;
import java.util.Set;

import org.sangam.channels.conversation.ConversationService;
import org.sangam.channels.messenger.OptInRegistry;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.web.Lists;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code /api/v1/channels}: conversations (participants only), live stream, opt-ins and channel status. */
@RestController
@RequestMapping("/api/v1/channels")
public class ChannelsController {

    public static final String READ = "channels.read";
    public static final String WRITE = "channels.write";
    public static final String ADMIN = "channels.admin";

    private final ConversationService conversations;
    private final OptInRegistry optIns;
    private final ChannelsLiveHub live;
    private final ChannelStatusSource status;
    private final PermissionPort permissions;

    public ChannelsController(ConversationService conversations, OptInRegistry optIns, ChannelsLiveHub live,
                              ChannelStatusSource status, PermissionPort permissions) {
        this.conversations = conversations;
        this.optIns = optIns;
        this.live = live;
        this.status = status;
        this.permissions = permissions;
    }

    public record CreateConversation(String title, String caseRef, String retentionClass, Set<String> participants) {
    }

    public record AddParticipant(String principal) {
    }

    public record SendMessage(String body, List<ConversationService.Attachment> attachments, String clientKey) {
    }

    public record OptInRequest(String channel, String externalId, String principalId, String evidence) {
    }

    @PostMapping("/conversations")
    public ResponseEntity<ConversationService.Conversation> create(RequestContext ctx, @RequestBody CreateConversation b) {
        permissions.require(ctx, WRITE);
        ConversationService.Conversation c = conversations.create(ctx.tenantId(), ctx.principalId(), b.title(), b.caseRef(),
                b.retentionClass() == null ? "standard" : b.retentionClass(), b.participants() == null ? Set.of() : b.participants());
        return Lists.created("/api/v1/channels/conversations/" + c.id(), c.id(), c);
    }

    /** Only conversations the caller participates in. */
    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationService.Conversation>> list(RequestContext ctx, @RequestParam(required = false) String caseRef,
            @RequestParam(defaultValue = "100") int limit) {
        permissions.require(ctx, READ);
        return Lists.ok(conversations.list(ctx.tenantId(), ctx.principalId(), caseRef, Math.min(Math.max(limit, 1), 500)));
    }

    @PostMapping("/conversations/{id}/participants")
    public ResponseEntity<Void> addParticipant(RequestContext ctx, @PathVariable String id, @RequestBody AddParticipant b) {
        permissions.require(ctx, WRITE);
        conversations.addParticipant(ctx.tenantId(), id, ctx.principalId(), b.principal());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<ConversationService.Message>> history(RequestContext ctx, @PathVariable String id,
            @RequestParam(defaultValue = "100") int limit) {
        permissions.require(ctx, READ);
        return Lists.ok(conversations.history(ctx.tenantId(), id, ctx.principalId(), Math.min(Math.max(limit, 1), 500)));
    }

    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<ConversationService.Message> send(RequestContext ctx, @PathVariable String id, @RequestBody SendMessage b) {
        permissions.require(ctx, WRITE);
        ConversationService.Message m = conversations.send(ctx.tenantId(), id, ctx.principalId(), b.body(),
                b.attachments() == null ? List.of() : b.attachments(),
                b.clientKey() != null ? b.clientKey() : ctx.idempotencyKey());
        return Lists.okRef(m.id(), m);
    }

    @PostMapping(value = "/conversations/{id}/attachments", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<ConversationService.Attachment> upload(RequestContext ctx, @PathVariable String id,
            @RequestParam String name, @RequestHeader(value = "X-Content-Type", required = false) String contentType,
            @RequestBody byte[] content) {
        permissions.require(ctx, WRITE);
        ConversationService.Attachment a = conversations.upload(ctx.tenantId(), id, ctx.principalId(), name,
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType, content);
        return Lists.okRef(a.key(), a);
    }

    @GetMapping(value = "/conversations/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(RequestContext ctx, @PathVariable String id) {
        permissions.require(ctx, READ);
        // history refuses anyone who is not a participant of this tenant's conversation.
        conversations.history(ctx.tenantId(), id, ctx.principalId(), 1);
        return live.subscribe(ctx.tenantId(), ctx.principalId(), id);
    }

    @GetMapping("/conversations/search")
    public ResponseEntity<List<ConversationService.Message>> search(RequestContext ctx, @RequestParam String text,
            @RequestParam(defaultValue = "50") int limit) {
        permissions.require(ctx, READ);
        return Lists.ok(conversations.search(ctx.tenantId(), ctx.principalId(), text, Math.min(Math.max(limit, 1), 200)));
    }

    @GetMapping("/opt-ins")
    public ResponseEntity<List<OptInRegistry.OptIn>> optIns(RequestContext ctx, @RequestParam(required = false) String channel) {
        permissions.require(ctx, ADMIN);
        return Lists.ok(optIns.list(ctx.tenantId(), channel));
    }

    @PostMapping("/opt-ins")
    public ResponseEntity<Void> optIn(RequestContext ctx, @RequestBody OptInRequest b) {
        permissions.require(ctx, ADMIN);
        optIns.optIn(ctx.tenantId(), b.channel(), b.externalId(), b.principalId(), b.evidence());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/opt-ins/withdraw")
    public ResponseEntity<Void> optOut(RequestContext ctx, @RequestBody OptInRequest b) {
        permissions.require(ctx, ADMIN);
        optIns.optOut(ctx.tenantId(), b.channel(), b.externalId(), b.principalId(), b.evidence());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/status")
    public ResponseEntity<List<ChannelStatusSource.ChannelStatus>> status(RequestContext ctx) {
        permissions.require(ctx, READ);
        return Lists.ok(status.status(ctx.tenantId()));
    }
}
