"""Tests package for relay bridge."""
import os

os.environ.setdefault("LLM_BASE_URL", "http://test-llm.example.local")
os.environ.setdefault("LLM_MODEL", "test-chat-model")
os.environ.setdefault("LLM_API_KEY", "test-llm-key")
