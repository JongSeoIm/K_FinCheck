from urllib.parse import quote

import httpx


class SpringError(Exception):
    """Spring 조회 실패. message 는 사용자에게 보여 줄 수 있는 문구입니다."""


class SpringClient:
    """Spring 의 /api/tools 조회 API 호출."""

    def __init__(self, http: httpx.AsyncClient):
        self.http = http

    async def _get(self, path: str, params: dict | None = None):
        try:
            response = await self.http.get(path, params=params)
        except httpx.TimeoutException as error:
            raise SpringError("자료 조회 시간이 초과되었습니다.") from error
        except httpx.HTTPError as error:
            raise SpringError("자료 서버에 연결하지 못했습니다.") from error

        try:
            body = response.json()
        except ValueError as error:
            raise SpringError("자료 서버 응답을 읽지 못했습니다.") from error
        if response.is_success:
            return body
        message = body.get("error") if isinstance(body, dict) else None
        raise SpringError(message or "자료를 조회하지 못했습니다.")

    async def all_companies(self) -> list[dict]:
        return await self._get("/api/tools/companies")

    async def companies(self, query: str) -> list[dict]:
        return await self._get("/api/tools/company", {"query": query})

    async def quote(self, code: str) -> dict:
        return await self._get(f"/api/tools/quote/{quote(code)}")

    async def financial(self, code: str, year: int | None) -> dict:
        params = {"year": year} if year else None
        return await self._get(f"/api/tools/financial/{quote(code)}", params)

    async def news(self, code: str) -> dict:
        return await self._get(f"/api/tools/news/{quote(code)}")