"""
配置层。

集中读取环境变量（来自 .env 文件或系统环境），供全项目使用。
这样所有「可调参数」都收敛到一处，而不是散落在代码里写死。

读取优先级（pydantic-settings 决定）：
    系统环境变量  >  .env 文件  >  这里定义的默认值

字段名与环境变量大小写无关：APP_ENV 自动映射到 app_env。
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """全局配置。字段默认值即「未配置时的安全默认」。"""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",  # .env 里有不认识的键时忽略，而不是报错
    )

    # ---- 服务基础 ----
    app_env: str = "development"          # development / production
    host: str = "0.0.0.0"
    port: int = 8080

    # ---- 能力提供方（可插拔的核心）----
    # 值对应 asr.py / stylize.py 工厂函数里注册的 provider 名称。
    # 接入真实 API 时，只需把这里改成新 provider 名 + 填好对应 key。
    asr_provider: str = "mock"            # mock | xunfei | whisper | ...
    llm_provider: str = "mock"            # mock | deepseek | qwen | claude | ...
    asr_api_key: str = ""
    llm_api_key: str = ""

    # ---- 接口鉴权 ----
    # 客户端需在请求头带 X-API-Token 才能调用 /v1/transcribe。
    # 留空（默认）= 不鉴权，行为与原型一致；生产环境填一个随机串即可开启。
    api_token: str = ""

    # ---- 请求限制 ----
    max_audio_mb: int = 10                # 单次请求音频解码后的大小上限
    request_timeout_seconds: int = 30     # 调用外部 API 的超时（mock 阶段未使用）

    # ---- CORS ----
    # 逗号分隔的来源列表，或 "*" 表示允许所有。
    # 生产环境务必收紧为具体域名，例如 "https://app.example.com"。
    cors_allow_origins: str = "*"

    @property
    def cors_origins_list(self) -> list[str]:
        """把逗号分隔的字符串拆成列表，供 CORS 中间件使用。"""
        return [o.strip() for o in self.cors_allow_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    """
    返回单例配置。

    用 lru_cache 保证整个进程只读取一次 .env，避免重复 IO；
    测试里也可通过 get_settings.cache_clear() 重置。
    """
    return Settings()