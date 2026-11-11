// Transfers under contention. Most transfers hit the same two accounts, so
// they queue on the pessimistic row locks; the rest spread over the other
// accounts. Money must be conserved: the sum of balances is checked at the end.
import { check } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { createAccount, fund, getBalance, transfer } from './lib.js';

const ACCOUNTS = Number(__ENV.ACCOUNTS || 10);
const INITIAL_BALANCE = 10000;
const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const HOT_SHARE = 0.8;

const unexpected = new Rate('transfer_unexpected_status');
const insufficientFunds = new Counter('transfer_insufficient_funds');

export const options = {
  setupTimeout: '120s',
  scenarios: {
    transfers: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    transfer_unexpected_status: ['rate<0.01'],
    'http_req_duration{name:transfer}': ['p(95)<1000'],
    // Listed so the summary compares contended and spread transfers.
    'http_req_duration{name:transfer,kind:hot}': ['p(95)<1000'],
    'http_req_duration{name:transfer,kind:spread}': ['p(95)<1000'],
    checks: ['rate==1.0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const accounts = [];
  for (let i = 0; i < ACCOUNTS; i += 1) {
    const id = createAccount();
    fund(id, INITIAL_BALANCE);
    accounts.push(id);
  }
  const total = accounts.reduce((sum, id) => sum + getBalance(id), 0);
  check(total, { 'accounts funded through the webhook': (t) => t === ACCOUNTS * INITIAL_BALANCE });
  return { accounts, total };
}

function pickPair(accounts) {
  if (Math.random() < HOT_SHARE) {
    return Math.random() < 0.5
      ? [accounts[0], accounts[1], 'hot']
      : [accounts[1], accounts[0], 'hot'];
  }
  const source = Math.floor(Math.random() * accounts.length);
  let destination = Math.floor(Math.random() * (accounts.length - 1));
  if (destination >= source) {
    destination += 1;
  }
  return [accounts[source], accounts[destination], 'spread'];
}

export default function (data) {
  const [source, destination, kind] = pickPair(data.accounts);
  const amount = kind === 'hot' ? 1 : 1 + Math.floor(Math.random() * 5);

  const res = transfer(source, destination, amount, { kind });

  unexpected.add(res.status !== 201 && res.status !== 422);
  if (res.status === 422) {
    insufficientFunds.add(1);
  }
}

export function teardown(data) {
  const total = data.accounts.reduce((sum, id) => sum + getBalance(id), 0);
  check(total, { 'money is conserved': (t) => Math.abs(t - data.total) < 0.0001 });
  console.log(`total balance before=${data.total} after=${total}`);
}
