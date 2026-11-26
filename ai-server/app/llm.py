"""Gemini 호출. 구조화 출력(JSON 스키마)으로 받아 형식이 어긋난 답을 쓰지 않게 한다.

테스트에서는 LlmClient를 가짜로 바꿔 끼운다(main.get_llm 의존성).
"""
from __future__ import annotations

import logging
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
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
    """Gemini 호출.

    Gemini API는 요청 마감 시간(deadline)을 최소 10초로만 받는다. 그래서 요청 자체에는 10초를 주고 SDK의 자동 재시도는 끈 뒤,
    AI 서버에서 따로 gemini_timeout_ms(기본 8초)만 기다린다. 메인 서버의 10초 제한(FDS_AI_001 #4) 안에 실패를 돌려주기 위해서다.
    시간이 넘으면 응답은 기다리지 않고 버린다(작업 스레드는 요청이 끝나면 정리된다).
    """

    GEMINI_MIN_DEADLINE_MS = 10_000

    def __init__(self, settings: Settings):
        self._settings = settings
        self._client = None
        self._pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="gemini")
        if settings.has_key:
            from google import genai
            from google.genai import types

            self._types = types
            self._client = genai.Client(
                api_key=settings.gemini_api_key,
                http_options=types.HttpOptions(
                    timeout=max(self.GEMINI_MIN_DEADLINE_MS, settings.gemini_timeout_ms),
                    retry_options=types.HttpRetryOptions(attempts=1),
                ),
            )

    def generate(self, system: str, prompt: str, schema: type[T]) -> T:
        if self._client is None:
            raise LlmError("GEMINI_API_KEY가 설정되지 않았습니다")
        future = self._pool.submit(self._call, system, prompt, schema)
        try:
            response = future.result(timeout=self._settings.gemini_timeout_ms / 1000)
        except FutureTimeout as e:
            future.cancel()
            log.warning("Gemini 응답이 %sms 안에 오지 않음", self._settings.gemini_timeout_ms)
            raise LlmError("Gemini 응답 시간 초과") from e
        except Exception as e:  # 네트워크·한도·모델 오류 모두 같은 실패로 본다
            log.warning("Gemini 호출 실패: %s %s", type(e).__name__, getattr(e, "code", ""))
            raise LlmError(f"Gemini 호출 실패: {type(e).__name__}") from e
        return self._parse(response, schema)

    def _call(self, system: str, prompt: str, schema: type[T]):
        types = self._types
        return self._client.models.generate_content(
            model=self._settings.gemini_model,
            contents=prompt,
            config=types.GenerateContentConfig(
                system_instruction=system,
                response_mime_type="application/json",
                response_schema=schema,
                temperature=0.2,
            ),
        )

    @staticmethod
    def _parse(response, schema: type[T]) -> T:
        parsed = getattr(response, "parsed", None)
        if isinstance(parsed, schema):
            return parsed
        try:
            return schema.model_validate_json(response.text or "")
        except Exception as e:
            raise LlmError("Gemini 답을 해석하지 못했습니다") from e
