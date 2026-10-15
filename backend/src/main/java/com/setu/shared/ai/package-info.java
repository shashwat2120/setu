/**
 * Text embeddings as a shared-kernel library: the {@link com.setu.shared.ai.TextEmbedder} contract and its implementations (a built-in
 * deterministic embedder, and Gemini or Ollama through Spring AI). They live here, not in ai-explain, so that institution can index and search
 * policy documents without depending on the AI service: when services are extracted, each service that needs embeddings uses this library
 * directly rather than calling another service (found by the Sprint 6 coupling audit, docs/extraction-audit.md).
 */
package com.setu.shared.ai;
