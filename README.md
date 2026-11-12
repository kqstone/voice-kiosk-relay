# voice-kiosk-relay

Generic Android voice kiosk, not tied to a specific phone model.

## Three-Tier Routing

1. Tier 1 (On-Device Local): Rule-based intent matching, local tools, and embedded models for offline low-latency responses.
2. Tier 2 (Bridge LLM Fast Answer): Edge relay service providing fast answers and conversational interactions.
3. Tier 3 (Muse Queue): Asynchronous task enqueueing and deep context execution routed to the Muse backend.

## Documentation

See [RELAY-BRIDGE-REPLAN.md](docs/RELAY-BRIDGE-REPLAN.md) for the full architecture and implementation plan.
