import http from 'k6/http';
import { check, fail } from 'k6';
import { Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const boardId = __ENV.BOARD_ID;
const vus = Number(__ENV.VUS || 1);
const tokens = (__ENV.ACCESS_TOKENS || __ENV.ACCESS_TOKEN || '')
  .split(',')
  .map((token) => token.trim())
  .filter(Boolean);
const stagingManifest = JSON.parse(open(__ENV.STAGING_MANIFEST || '/tmp/k6-post-image-staging.json'));

if (!boardId) fail('BOARD_ID is required');
if (tokens.length < vus) {
  fail(`ACCESS_TOKENS requires at least ${vus} tokens because each VU must use a separate test user`);
}
if (!Array.isArray(stagingManifest) || stagingManifest.length < vus) {
  fail(`STAGING_MANIFEST requires at least ${vus} entries`);
}

const postCreateDuration = new Trend('post_only_create_duration', true);

export const options = {
  scenarios: {
    post_image_post_only: {
      executor: 'per-vu-iterations',
      vus,
      iterations: 1,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    post_only_create_duration: ['p(95)<2000'],
  },
};

export default function () {
  const token = tokens[__VU - 1];
  const staging = stagingManifest[__VU - 1];
  const unique = `${Date.now()}-${__VU}`;
  const response = http.post(
    `${baseUrl}/api/v1/boards/${boardId}/posts`,
    JSON.stringify({
      content: `k6 post-only bottleneck test ${unique}`,
      isAnonymous: false,
      imageUrls: staging.keys,
    }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json',
      },
      tags: { name: 'POST /api/v1/boards/:boardId/posts post-only' },
    },
  );
  postCreateDuration.add(response.timings.duration);

  if (!check(response, {
    'post-only create status is 201': (result) => result.status === 201,
  })) {
    console.error(`post-only create failed: status=${response.status} body=${response.body}`);
  }
}
