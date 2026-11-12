# Sangam SDK FastAPI adapter (`sangam_sdk_fastapi`)

**Deferred past 0.1.0 (decision 131): this adapter is not built or published.**

M-027: starts the Sangam Python SDK worker (`sangam_sdk.Worker`) with a FastAPI / Starlette application and stops it
gracefully on shutdown, through the ASGI lifespan. A separate distribution: the core SDK keeps zero runtime
dependencies. Checked against FastAPI 0.135.3 and Starlette 1.0.0 (lifespan = `app -> async context manager`).

```python
from fastapi import Depends, FastAPI
from sangam_sdk import TopicSubscription
from sangam_sdk_fastapi import WorkerSettings, get_worker, sangam_lifespan

app = FastAPI(lifespan=sangam_lifespan(
    lambda app: WorkerSettings.from_env().build_worker(),     # SANGAM_URL, SANGAM_TOKEN, SANGAM_WORKER_ID, ...
    subscriptions=[TopicSubscription("payment", pay, irreversible=True)],
    sagas=[charge_saga]))

@app.get("/worker")
def info(worker=Depends(get_worker)):
    return {"workerId": worker.worker_id, "inFlight": worker.in_flight_count}
```

- Startup: the factory (or the given worker) is resolved, subscriptions and sagas are registered, `Worker.start()`.
- Shutdown: `Worker.stop(stop_timeout)` runs in a thread (`anyio.to_thread`), so in-flight handlers drain while the
  event loop keeps running; remaining items are cancelled and unlocked by the SDK.
- An existing lifespan: `sangam_lifespan(..., inner=existing)` or `install(app, worker)`.
- The worker is in the lifespan state (`request.state.sangam_worker`) and on `app.state`.

Django: not provided in 0.1.0 (Django is not installed in the build environment; see the SDK README).

Tests: `tests/test_lifespan.py` (pytest; drive the lifespan directly, no HTTP client).