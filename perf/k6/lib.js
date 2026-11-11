// Shared helpers for the k6 scenarios. Run them through perf/run.sh, which
// provides BASE_URL, TOKEN and the other environment variables.
import http from 'k6/http';
import crypto from 'k6/crypto';
import { check, fail } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://api-gateway:8080';
export const FAKE_STRIPE_URL = __ENV.FAKE_STRIPE_URL || 'http://fake-stripe:8080';
const TOKEN = __ENV.TOKEN;
// Must match STRIPE_WEBHOOK_SECRET in perf/docker-compose.perf.yml.
const WEBHOOK_SECRET = __ENV.WEBHOOK_SECRET || 'whsec_perf_only';

if (!TOKEN) {
  fail('TOKEN is not set; run the scenarios through perf/run.sh');
}

let sequence = 0;

/** Unique enough for an Idempotency-Key within one run. */
export function uniqueKey(prefix) {
  sequence += 1;
  return `${prefix}-${__VU}-${sequence}-${Date.now()}-${Math.floor(Math.random() * 1e9)}`;
}

export function authHeaders(extra = {}) {
  return Object.assign({
    Authorization: `Bearer ${TOKEN}`,
    'Content-Type': 'application/json',
  }, extra);
}

export function createAccount() {
  const res = http.post(`${BASE_URL}/accounts`, JSON.stringify({ currency: 'USD' }), {
    headers: authHeaders(),
    tags: { name: 'create account' },
  });
  if (!check(res, { 'account created': (r) => r.status === 201 })) {
    fail(`account creation failed: ${res.status} ${res.body}`);
  }
  return res.json('id');
}

export function getBalance(accountId) {
  const res = http.get(`${BASE_URL}/accounts/${accountId}`, {
    headers: authHeaders(),
    tags: { name: 'get account' },
  });
  if (res.status !== 200) {
    fail(`account read failed: ${res.status} ${res.body}`);
  }
  return Number(res.json('balance'));
}

/** POST /deposits; returns the raw response so callers can check 201 vs 503. */
export function requestDeposit(accountId, amount, tags = {}) {
  return http.post(
    `${BASE_URL}/deposits`,
    JSON.stringify({ accountId, amount, currency: 'USD' }),
    {
      headers: authHeaders({ 'Idempotency-Key': uniqueKey('dep') }),
      tags: Object.assign({ name: 'create deposit' }, tags),
      responseCallback: http.expectedStatuses(201, 503),
    },
  );
}

/** The fake Stripe returns http://fake-stripe.local/pay/<sessionId>. */
export function sessionIdOf(depositResponse) {
  const url = depositResponse.json('checkoutUrl');
  return url.substring(url.lastIndexOf('/') + 1);
}

/** A checkout.session.completed event for a paid session, as Stripe sends it. */
export function paidSessionEvent(eventId, sessionId, depositId, amountMinor) {
  return JSON.stringify({
    id: eventId,
    object: 'event',
    api_version: '2026-10-28.endive',
    created: Math.floor(Date.now() / 1000),
    livemode: false,
    pending_webhooks: 1,
    type: 'checkout.session.completed',
    data: {
      object: {
        id: sessionId,
        object: 'checkout.session',
        amount_total: amountMinor,
        currency: 'usd',
        client_reference_id: depositId,
        metadata: { depositId },
        mode: 'payment',
        payment_intent: `pi_${sessionId}`,
        payment_status: 'paid',
        status: 'complete',
      },
    },
  });
}

/** Stripe-Signature header: t=<ts>,v1=hex(HMAC-SHA256(secret, "<ts>.<payload>")). */
export function sign(payload) {
  const timestamp = Math.floor(Date.now() / 1000);
  const signature = crypto.hmac('sha256', WEBHOOK_SECRET, `${timestamp}.${payload}`, 'hex');
  return `t=${timestamp},v1=${signature}`;
}

export function sendWebhook(payload, tags = {}) {
  return http.post(`${BASE_URL}/webhooks/stripe`, payload, {
    headers: { 'Content-Type': 'application/json', 'Stripe-Signature': sign(payload) },
    tags: Object.assign({ name: 'stripe webhook' }, tags),
  });
}

/** Funds an account through the real path: deposit, then a signed webhook. */
export function fund(accountId, amount) {
  const deposit = requestDeposit(accountId, amount);
  if (deposit.status !== 201) {
    fail(`deposit failed: ${deposit.status} ${deposit.body}`);
  }
  const event = paidSessionEvent(
    `evt_${uniqueKey('fund')}`,
    sessionIdOf(deposit),
    deposit.json('depositId'),
    Math.round(amount * 100),
  );
  const webhook = sendWebhook(event);
  if (webhook.status !== 200) {
    fail(`webhook failed: ${webhook.status} ${webhook.body}`);
  }
  return deposit.json('depositId');
}

export function transfer(sourceAccountId, destinationAccountId, amount, tags = {}) {
  return http.post(
    `${BASE_URL}/transfers`,
    JSON.stringify({ sourceAccountId, destinationAccountId, amount }),
    {
      headers: authHeaders({ 'Idempotency-Key': uniqueKey('xfer') }),
      tags: Object.assign({ name: 'transfer' }, tags),
      // 422 = insufficient funds: a valid business answer, not a failure.
      responseCallback: http.expectedStatuses(201, 422),
    },
  );
}
