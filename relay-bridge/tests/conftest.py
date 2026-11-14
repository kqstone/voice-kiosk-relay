import os

# Ensure required environment variables for test suite run
os.environ.setdefault("LLM_BASE_URL", "http://test-llm.example.local")
os.environ.setdefault("LLM_MODEL", "test-chat-model")
os.environ.setdefault("LLM_API_KEY", "test-llm-key")
