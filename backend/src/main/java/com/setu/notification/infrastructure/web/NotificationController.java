package com.setu.notification.infrastructure.web;

import com.setu.notification.application.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The signed-in person's own notifications. There is no user or tenant parameter anywhere: whose notifications these are comes only
 * from the authenticated session.
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Newest first; {@code unreadCount} covers all of the caller's unread notifications, not just the page returned. */
    @GetMapping
    public NotificationService.NotificationPage list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                      @RequestParam(defaultValue = "" + NotificationService.DEFAULT_LIMIT) int limit) {
        return notificationService.listMine(unreadOnly, limit);
    }

    public record ReadResponse(UUID id, Instant readAt) {
    }

    public record ReadAllResponse(int updated) {
    }

    /** 404 for a notification that is not the caller's, exactly as for one that does not exist. */
    @PostMapping("/{id}/read")
    public ReadResponse markRead(@PathVariable UUID id) {
        Instant readAt = notificationService.markRead(id).orElseThrow(NoSuchElementException::new);
        return new ReadResponse(id, readAt);
    }

    @PostMapping("/read-all")
    public ResponseEntity<ReadAllResponse> markAllRead() {
        return ResponseEntity.ok(new ReadAllResponse(notificationService.markAllRead()));
    }
}
