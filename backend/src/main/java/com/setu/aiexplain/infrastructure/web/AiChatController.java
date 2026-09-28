package com.setu.aiexplain.infrastructure.web;

import com.setu.aiexplain.application.AiChatService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Day 14 §20: the one AI API endpoint. The request is intent, not an
 * implementation command — {@code message} is the user's own literal
 * question; nothing else about the request (no tenant id, no repository
 * name, no tool name, no SQL) is ever accepted from the client.
 */
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiChatService aiChatService;

    public AiChatController(AiChatService aiChatService) {
        this.aiChatService = aiChatService;
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer, List<String> sources, Instant generatedAt, String model, UUID correlationId) {
    }

    public record ChatErrorResponse(String reason) {
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        var result = aiChatService.chat(request == null ? null : request.message());
        return switch (result) {
            case AiChatService.ChatResult.Success r ->
                ResponseEntity.ok(new ChatResponse(r.answer(), r.sources(), r.generatedAt(), r.model(), r.correlationId()));
            case AiChatService.ChatResult.InvalidRequest r ->
                ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ChatErrorResponse(r.reason()));
            case AiChatService.ChatResult.RateLimited ignored ->
                ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(new ChatErrorResponse("AI request rate limit exceeded, try again shortly"));
            case AiChatService.ChatResult.ProviderUnavailable ignored ->
                ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ChatErrorResponse("AI service is currently unavailable"));
        };
    }
}
