/**
 * Day 14 §AI: the AI orchestration/tool layer — see {@link
 * com.setu.aiexplain.application.AiToolService} and {@link
 * com.setu.aiexplain.application.AiChatService} for the full architecture.
 * No JPA entities live in this module at all (AGENTS.md: "the ai-explain
 * module never gets a datasource") — every read goes through another
 * module's already-authorized or narrow no-auth cross-module port.
 */
package com.setu.aiexplain.application;
