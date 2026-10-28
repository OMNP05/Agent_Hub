from fastapi import APIRouter

router = APIRouter(tags=["health"])


@router.get("/healthz")
async def healthz() -> dict[str, str]:
    """Liveness only. It never touches the database, so a sleeping database cannot fail a deploy check."""
    return {"status": "ok"}
