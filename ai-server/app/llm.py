"""Gemini 호출. 구조화 출력(JSON 스키마)으로 받아 형식이 어긋난 답을 쓰지 않게 한다.

테스트에서는 LlmClient를 가짜로 바꿔 끼운다(main.get_llm 의존성).
"""
from __future__ import annotations

import logging
from typing import Protocol, TypeVar

from pydantic import BaseModel

from .config import Settings

log = logging.getLogger(__name__)
T = TypeVar("T", bound=BaseModel)


class LlmError(RuntimeError):
    """AI를 쓸 수 없거나(키 없음·한도·시간 초과) 답을 해석할 수 없음"""


class LlmClient(Protocol):
    def generate(self, system: str, prompt: str, schema: type[T]) -> T: ...


class GeminiClient:
    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = None
        if settings.has_key:
            from google import genai
            from google.genai import types

            self._types = types
            self._client = genai.Client(
                api_key=settings.gemini_api_key,
                http_options=types.HttpOptions(timeout=settings.gemini_timeout_ms),
            )

    def generate(self, system: str, prompt: str, schema: type[T]) -> T:
        if self._client is None:
            raise LlmError("GEMINI_API_KEY가 설정되지 않았습니다")
        types = self._types
        try:
            response = self._client.models.generate_content(
                model=self._settings.gemini_model,
                contents=prompt,
                config=types.GenerateContentConfig(
                    system_instruction=system,
                    response_mime_type="application/json",
                    response_schema=schema,
                    temperature=0.2,
                ),
            )
        except Exception as e:  # 네트워크·한도·시간 초과 모두 같은 실패로 본다
            log.warning("Gemini 호출 실패: %s", type(e).__name__)
            raise LlmError(f"Gemini 호출 실패: {type(e).__name__}") from e
        parsed = getattr(response, "parsed", None)
        if isinstance(parsed, schema):
            return parsed
        try:
            return schema.model_validate_json(response.text or "")
        except Exception as e:
            raise LlmError("Gemini 답을 해석하지 못했습니다") from e
