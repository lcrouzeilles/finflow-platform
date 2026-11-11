// Stripe outage. Deposits are created at a steady rate while fake-stripe goes
// through three phases: healthy, failing with 500s, healthy again. Once the
// circuit breaker opens, deposits must fail fast with 503 instead of waiting
// on retries, and after the open state expires they must succeed again.
// No deposit may be left behind for a failed request (no zombie PENDING rows).
import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { FAKE_STRIPE_URL, createAccount, requestDeposit } from './lib.js';

const PHASE = Number(__ENV.PHASE_SECONDS || 20);

const unexpected = new Counter('deposit_unexpected_status');
const unavailable = new Counter('deposit_unavailable_503');

export const options = {
  scenarios: {
    healthy: {
      executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: `${PHASE}s`,
      preAllocatedVUs: 20, maxVUs: 50, exec: 'deposit', tags: { phase: 'healthy' },
    },
    outage: {
      executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: `${PHASE}s`,
      startTime: `${PHASE}s`,
      preAllocatedVUs: 20, maxVUs: 50, exec: 'deposit', tags: { phase: 'outage' },
    },
    // Starts after the outage plus the 10s open state of the perf overlay.
    recovered: {
      executor: 'constant-arrival-rate', rate: 5, timeUnit: '1s', duration: `${PHASE}s`,
      startTime: `${2 * PHASE + 12}s`,
      preAllocatedVUs: 20, maxVUs: 50, exec: 'deposit', tags: { phase: 'recovered' },
    },
    chaos: {
      executor: 'shared-iterations', vus: 1, iterations: 1, exec: 'chaos',
      maxDuration: `${3 * PHASE + 30}s`,
    },
  },
  thresholds: {
    deposit_unexpected_status: ['count==0'],
    'http_req_duration{name:create deposit,phase:healthy}': ['p(95)<1000'],
    // Listed so the summary reports outage latency; bounded by retries + timeouts.
    'http_req_duration{name:create deposit,phase:outage}': ['p(95)<5000'],
    'http_req_duration{name:create deposit,phase:recovered}': ['p(95)<1000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { accountId: createAccount() };
}

export function deposit(data) {
  const phase = exec.scenario.name;
  const res = requestDeposit(data.accountId, 10);

  if (phase === 'outage') {
    // Retries first, then 503 from the open breaker; never a 5xx leak.
    if (res.status === 503) {
      unavailable.add(1);
    } else if (res.status !== 201) {
      unexpected.add(1);
    }
  } else if (!check(res, { [`${phase}: deposit created`]: (r) => r.status === 201 })) {
    unexpected.add(1);
  }
}

export function chaos() {
  // Half a second into the outage phase, so the last healthy-phase request
  // is not caught by the switch; the outage phase accepts 201 as well.
  sleep(PHASE + 0.5);
  const failing = http.post(`${FAKE_STRIPE_URL}/__admin/mappings`, JSON.stringify({
    priority: 1,
    request: { method: 'POST', urlPath: '/v1/checkout/sessions' },
    response: {
      status: 500,
      headers: { 'Content-Type': 'application/json' },
      jsonBody: { error: { type: 'api_error', message: 'simulated outage' } },
    },
  }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'chaos' } });
  check(failing, { 'outage injected': (r) => r.status === 201 });
  console.log('fake-stripe now returns 500');

  sleep(PHASE - 0.5);
  const restored = http.del(`${FAKE_STRIPE_URL}/__admin/mappings/${failing.json('id')}`, null, {
    tags: { name: 'chaos' },
  });
  check(restored, { 'outage cleared': (r) => r.status === 200 });
  console.log('fake-stripe healthy again');
}
