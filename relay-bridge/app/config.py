import os
from pathlib import Path

# Load variables from .env file if python-dotenv is installed
try:
    from dotenv import load_dotenv
    load_dotenv()
except ImportError:
    pass


class Settings:
    port: int = int(os.environ.get("PORT", "8093"))
    data_dir: Path = Path(os.environ.get("DATA_DIR", "data"))
    push_token: str = os.environ.get("PUSH_TOKEN", "")
    device_token: str = os.environ.get("DEVICE_TOKEN", "")
    tz: str = os.environ.get("TZ", "Asia/Shanghai")


settings = Settings()
