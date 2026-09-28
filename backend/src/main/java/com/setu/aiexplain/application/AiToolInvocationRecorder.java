package com.setu.aiexplain.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records which tools were actually invoked during one chat call, purely
 * for source attribution in {@link AiChatService}'s response (canonical
 * spec §10's "every answer carries source references" rule) — never tool
 * arguments or results, only the tool name, since those may contain
 * per-student data that has no business sitting in a response-metadata
 * field.
 * <p>
 * {@code ThreadLocal}-backed rather than request-scoped: Spring AI
 * executes {@code @Tool} methods synchronously on the calling thread
 * inside {@code ChatClient.call()}, so the thread that starts a chat
 * request is the same thread every tool call for that request runs on —
 * the same assumption every {@code AuthorizationContext}/{@code
 * SecurityContextHolder} call in this codebase already relies on.
 */
@Component
class AiToolInvocationRecorder {

    private final ThreadLocal<List<String>> invoked = ThreadLocal.withInitial(ArrayList::new);

    void record(String toolName) {
        invoked.get().add(toolName);
    }

    List<String> drain() {
        List<String> result = Collections.unmodifiableList(new ArrayList<>(invoked.get()));
        invoked.remove();
        return result;
    }
}
