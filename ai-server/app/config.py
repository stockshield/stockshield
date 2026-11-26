"""AI 서버 설정. 값은 환경 변수에서 읽고, 없으면 저장소의 infra/.env를 읽는다 (SYS_SEC_001 #2).

API 키는 로그·응답에 남기지 않는다.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _load_dotenv() -> dict[str, str]:
    """infra/.env를 KEY=VALUE 형식으로 읽는다. 이미 있는 환경 변수가 우선한다."""
    here = Path(__file__).resolve()
    for candidate in (here.parents[2] / "infra" / ".env", Path.cwd() / "infra" / ".env",
                      Path.cwd().parent / "infra" / ".env"):
        if candidate.is_file():
            values: dict[str, str] = {}
            for line in candidate.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
            return values
    return {}


@dataclass(frozen=True)
class Settings:
    gemini_api_key: str
    gemini_model: str
    gemini_timeout_ms: int

    @property
    def has_key(self) -> bool:
        return bool(self.gemini_api_key)


def load_settings() -> Settings:
    file_values = _load_dotenv()

    def get(name: str, default: str) -> str:
        return os.environ.get(name) or file_values.get(name) or default

    return Settings(
        gemini_api_key=get("GEMINI_API_KEY", ""),
        gemini_model=get("GEMINI_MODEL", "gemini-3.1-flash-lite"),
        # 메인 서버는 10초 안에 응답이 없으면 실패로 처리한다(FDS_AI_001 #4). Gemini 호출은 그보다 짧게 끊는다
        gemini_timeout_ms=int(get("GEMINI_TIMEOUT_MS", "8000")),
    )
