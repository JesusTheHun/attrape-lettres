import { captureLogs } from "../src/log.js";

// The service logs a line per request on purpose, which would bury 46 tests in
// its own output. Swapping the sink once here silences all of it; a spec that
// wants to ASSERT on a log calls `captureLogs()` itself and restores to this.
captureLogs();
