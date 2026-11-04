// SPDX-License-Identifier: Apache-2.0
package org.sangam.rest.web;

import java.util.List;

import org.sangam.rest.port.EventPort;
import org.sangam.rest.port.FormPort;
import org.sangam.rest.port.PermissionPort;
import org.sangam.rest.port.RequestContext;
import org.sangam.rest.support.Permissions;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Event registry (FC-02..04, R38, R83) and forms contract (R47, FC-39). */
@RestController
@RequestMapping("/api/v1")
public class EventFormController {

    private final EventPort events;
    private final FormPort forms;
    private final PermissionPort permissions;

    public EventFormController(EventPort events, FormPort forms, PermissionPort permissions) {
        this.events = events;
        this.forms = forms;
        this.permissions = permissions;
    }

    // ---------------------------------------------------------------- events

    @GetMapping("/events/channels")
    public ResponseEntity<List<EventPort.Channel>> channels(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        return Lists.ok(events.channels(ctx, Lists.page(page, size)));
    }

    @PostMapping("/events/channels")
    public ResponseEntity<EventPort.Channel> createChannel(RequestContext ctx, @RequestBody EventPort.Channel body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        EventPort.Channel c = events.createChannel(ctx, body);
        return Lists.created("/api/v1/events/channels/" + c.key(), c.key(), c);
    }

    @GetMapping("/events/channels/{key}")
    public EventPort.Channel channel(RequestContext ctx, @PathVariable String key) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        return events.channel(ctx, key);
    }

    @PutMapping("/events/channels/{key}")
    public ResponseEntity<EventPort.Channel> updateChannel(RequestContext ctx, @PathVariable String key,
                                                           @RequestBody EventPort.Channel body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        return Lists.okRef(key, events.updateChannel(ctx, key, body));
    }

    @DeleteMapping("/events/channels/{key}")
    public ResponseEntity<Void> deleteChannel(RequestContext ctx, @PathVariable String key,
                                              @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(reason);
        events.deleteChannel(ctx, key, reason);
        return ResponseEntity.noContent().build();
    }

    /** Body of the retire / restore commands. */
    public record ReasonBody(String reason) {
    }

    @PostMapping("/events/channels/{key}/retire")
    public ResponseEntity<EventPort.Channel> retireChannel(RequestContext ctx, @PathVariable String key,
                                                           @RequestBody ReasonBody body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(key, events.retireChannel(ctx, key, body.reason()));
    }

    @PostMapping("/events/channels/{key}/restore")
    public ResponseEntity<EventPort.Channel> restoreChannel(RequestContext ctx, @PathVariable String key,
                                                            @RequestBody ReasonBody body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(key, events.restoreChannel(ctx, key, body.reason()));
    }

    @PostMapping("/events/definitions/{key}/retire")
    public ResponseEntity<EventPort.EventDefinition> retireDefinition(RequestContext ctx, @PathVariable String key,
                                                                      @RequestBody ReasonBody body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(key, events.retireDefinition(ctx, key, body.reason()));
    }

    @PostMapping("/events/definitions/{key}/restore")
    public ResponseEntity<EventPort.EventDefinition> restoreDefinition(RequestContext ctx, @PathVariable String key,
                                                                       @RequestBody ReasonBody body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(body.reason());
        return Lists.okRef(key, events.restoreDefinition(ctx, key, body.reason()));
    }

    @GetMapping("/events/definitions")
    public ResponseEntity<List<EventPort.EventDefinition>> definitions(RequestContext ctx,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        return Lists.ok(events.definitions(ctx, Lists.page(page, size)));
    }

    @PostMapping("/events/definitions")
    public ResponseEntity<EventPort.EventDefinition> createDefinition(RequestContext ctx,
                                                                      @RequestBody EventPort.EventDefinition body) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        EventPort.EventDefinition d = events.createDefinition(ctx, body);
        return Lists.created("/api/v1/events/definitions/" + d.key(), d.key(), d);
    }

    @GetMapping("/events/definitions/{key}")
    public EventPort.EventDefinition definition(RequestContext ctx, @PathVariable String key) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        return events.definition(ctx, key);
    }

    @DeleteMapping("/events/definitions/{key}")
    public ResponseEntity<Void> deleteDefinition(RequestContext ctx, @PathVariable String key,
                                                 @RequestParam(required = false) String reason) {
        permissions.require(ctx, Permissions.EVENT_ADMIN);
        ProcessController.requireReason(reason);
        events.deleteDefinition(ctx, key, reason);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/events/{channel}", consumes = {"application/cloudevents+json", "application/json"})
    public ResponseEntity<EventPort.PublishResult> publish(RequestContext ctx, @PathVariable String channel,
                                                           @RequestBody EventPort.CloudEvent body) {
        permissions.require(ctx, Permissions.EVENT_PUBLISH);
        EventPort.PublishResult r = events.publish(ctx, channel, body);
        return Lists.accepted(r.eventId(), r);
    }

    // ---------------------------------------------------------------- forms (literal /forms/drafts wins over /forms/{key})

    @GetMapping("/forms/drafts")
    public ResponseEntity<List<FormPort.Draft>> drafts(RequestContext ctx, @RequestParam(required = false) String taskId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        permissions.require(ctx, Permissions.FORM_DRAFT);
        return Lists.ok(forms.drafts(ctx, taskId, Lists.page(page, size)));
    }

    @PostMapping("/forms/drafts")
    public ResponseEntity<FormPort.Draft> createDraft(RequestContext ctx, @RequestBody FormPort.SaveDraftRequest body) {
        permissions.require(ctx, Permissions.FORM_DRAFT);
        FormPort.Draft d = forms.createDraft(ctx, body);
        return ResponseEntity.created(java.net.URI.create("/api/v1/forms/drafts/" + d.draftId()))
                .header(Lists.RESOURCE_REF, d.draftId()).eTag("\"" + d.revision() + "\"").body(d);
    }

    @GetMapping("/forms/drafts/{draftId}")
    public ResponseEntity<FormPort.Draft> draft(RequestContext ctx, @PathVariable String draftId) {
        permissions.require(ctx, Permissions.FORM_DRAFT);
        FormPort.Draft d = forms.draft(ctx, draftId);
        return Lists.withRevision(d.revision(), d);
    }

    @PutMapping("/forms/drafts/{draftId}")
    public ResponseEntity<FormPort.Draft> saveDraft(RequestContext ctx, @PathVariable String draftId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestBody FormPort.SaveDraftRequest body) {
        permissions.require(ctx, Permissions.FORM_DRAFT);
        FormPort.Draft d = forms.saveDraft(ctx, draftId, Lists.ifMatchRevision(ifMatch), body);
        return ResponseEntity.ok().header(Lists.RESOURCE_REF, draftId).eTag("\"" + d.revision() + "\"").body(d);
    }

    @DeleteMapping("/forms/drafts/{draftId}")
    public ResponseEntity<Void> deleteDraft(RequestContext ctx, @PathVariable String draftId) {
        permissions.require(ctx, Permissions.FORM_DRAFT);
        forms.deleteDraft(ctx, draftId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/forms/{key}")
    public FormPort.FormVersion form(RequestContext ctx, @PathVariable String key,
                                     @RequestParam(required = false) String version) {
        permissions.require(ctx, Permissions.FORM_READ);
        return forms.get(ctx, key, version);
    }

    @PostMapping("/forms/{key}/validate")
    public FormPort.ValidationResult validate(RequestContext ctx, @PathVariable String key,
                                              @RequestBody FormPort.ValidateRequest body) {
        permissions.require(ctx, Permissions.FORM_READ);
        return forms.validate(ctx, key, body);
    }
}
