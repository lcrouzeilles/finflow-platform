// Stripe delivers at least once. The same checkout.session.completed event is
// sent many times concurrently; every delivery must be acknowledged with 200
// and the account must be credited exactly once.
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import {
  createAccount, getBalance, paidSessionEvent, requestDeposit, sendWebhook,
  sessionIdOf, uniqueKey,
} from './lib.js';

const DELIVERIES = Number(__ENV.DELIVERIES || 200);
const VUS = Number(__ENV.VUS || 50);
const AMOUNT = 250;

const notAcknowledged = new Rate('webhook_not_acknowledged');

export const options = {
  scenarios: {
    duplicates: {
      executor: 'shared-iterations',
      vus: VUS,
      iterations: DELIVERIES,
      maxDuration: '60s',
    },
  },
  thresholds: {
    webhook_not_acknowledged: ['rate==0'],
    checks: ['rate==1.0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const accountId = createAccount();
  const deposit = requestDeposit(accountId, AMOUNT);
  check(deposit, { 'deposit created': (r) => r.status === 201 });

  const event = paidSessionEvent(
    `evt_${uniqueKey('dup')}`,
    sessionIdOf(deposit),
    deposit.json('depositId'),
    AMOUNT * 100,
  );
  return { accountId, event };
}

export default function (data) {
  // Each delivery is re-signed with a fresh timestamp, like a Stripe retry.
  const res = sendWebhook(data.event);
  notAcknowledged.add(res.status !== 200);
}

export function teardown(data) {
  const balance = getBalance(data.accountId);
  check(balance, { 'credited exactly once': (b) => b === AMOUNT });
  console.log(`deliveries=${DELIVERIES} balance=${balance} (expected ${AMOUNT})`);
}
