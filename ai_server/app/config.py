from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """환경 변수 또는 ai_server/.env 에서 읽는 설정."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = "b2b16410517f6b18040f8d27d5b52ca5174fb107"
    openai_model: str = "gpt-5.4-mini"
    openai_timeout_seconds: float = 30
    spring_base_url: str = "http://localhost:8080"
    tool_timeout_seconds: float = 5