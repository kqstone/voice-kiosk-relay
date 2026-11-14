import os
from pathlib import Path

# Load variables from .env file if python-dotenv is installed
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass


class Settings:
    port: int
    data_dir: Path
    push_token: str
    device_token: str
    tz: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    tavily_api_key: str
    tavily_api_url: str
    search_enabled: bool
    search_timeout: float
    search_max_results: int
    search_hourly_limit: int
    memory_enabled: bool
    memory_db_path: Path

    def __init__(self) -> None:
        self.port = int(os.environ.get("PORT", "8093"))
        self.data_dir = Path(os.environ.get("DATA_DIR", "data"))
        self.push_token = os.environ.get("PUSH_TOKEN", "")
        self.device_token = os.environ.get("DEVICE_TOKEN", "")
        self.tz = os.environ.get("TZ", "Asia/Shanghai")

        # Required LLM settings: fail fast at startup if missing or empty
        base_url = os.environ.get("LLM_BASE_URL", "").strip()
        if not base_url:
            raise RuntimeError("LLM_BASE_URL environment variable is required")
        self.llm_base_url = base_url

        self.llm_api_key = os.environ.get("LLM_API_KEY", "")

        model = os.environ.get("LLM_MODEL", "").strip()
        if not model:
            raise RuntimeError("LLM_MODEL environment variable is required")
        self.llm_model = model

        # Tavily search settings
        self.tavily_api_key = os.environ.get("TAVILY_API_KEY", "")
        self.tavily_api_url = os.environ.get("TAVILY_API_URL", "https://api.tavily.com/search")
        self.search_enabled = os.environ.get("SEARCH_ENABLED", "true").lower() in ("true", "1", "yes")
        self.search_timeout = float(os.environ.get("SEARCH_TIMEOUT", "2.5"))
        self.search_max_results = int(os.environ.get("SEARCH_MAX_RESULTS", "3"))
        self.search_hourly_limit = int(os.environ.get("SEARCH_HOURLY_LIMIT", "60"))

        # Long-term memory settings
        self.memory_enabled = os.environ.get("MEMORY_ENABLED", "true").lower() in ("true", "1", "yes")
        memory_db_env = os.environ.get("MEMORY_DB_PATH", "").strip()
        if memory_db_env:
            self.memory_db_path = Path(memory_db_env)
        else:
            self.memory_db_path = self.data_dir / "memory.db"


settings = Settings()
