import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const boardId = __ENV.BOARD_ID;
const imageCount = Number(__ENV.IMAGE_COUNT || 5);
const vus = Number(__ENV.VUS || 1);
const iterations = Number(__ENV.ITERATIONS || 1);
const imageFilePath = __ENV.IMAGE_FILE;
const tokens = (__ENV.ACCESS_TOKENS || __ENV.ACCESS_TOKEN || '')
  .split(',')
  .map((token) => token.trim())
  .filter(Boolean);

if (!boardId) fail('BOARD_ID is required');
if (!imageFilePath) fail('IMAGE_FILE is required');
if (tokens.length === 0) fail('ACCESS_TOKEN or ACCESS_TOKENS is required');
if (imageCount < 1 || imageCount > 5) fail('IMAGE_COUNT must be between 1 and 5');
if (iterations > 3) fail('ITERATIONS must be 3 or less because one user can create only 3 posts per 5 minutes');
if (tokens.length < vus) {
  fail(`ACCESS_TOKENS requires at least ${vus} tokens because each VU must use a separate test user`);
}

const imageBytes = open(imageFilePath, 'b');
const presignDuration = new Trend('post_image_presign_duration', true);
const objectUploadDuration = new Trend('post_image_object_upload_duration', true);
const postCreateDuration = new Trend('post_image_create_duration', true);
const uploadedBytes = new Counter('post_image_uploaded_bytes');

export const options = {
  scenarios: {
    post_image_create: {
      executor: 'per-vu-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    post_image_presign_duration: ['p(95)<1000'],
    post_image_object_upload_duration: ['p(95)<10000'],
    post_image_create_duration: ['p(95)<2000'],
  },
};

export default function () {
  const token = tokens[__VU - 1];
  const unique = `${Date.now()}-${__VU}-${__ITER}`;
  const authHeaders = {
    Authorization: `Bearer ${token}`,
    'Content-Type': 'application/json',
  };

  const presignResponse = http.post(
    `${baseUrl}/api/v1/images/presign`,
    JSON.stringify({
      imageType: 'POST',
      uploadSessionId: `k6-${unique}`,
      files: Array.from({ length: imageCount }, (_, index) => ({
        filename: `k6-${unique}-${index}.jpg`,
        contentType: 'image/jpeg',
      })),
    }),
    { headers: authHeaders, tags: { name: 'POST /api/v1/images/presign' } },
  );
  presignDuration.add(presignResponse.timings.duration);

  const presignOk = check(presignResponse, {
    'presign status is 200': (response) => response.status === 200,
  });
  if (!presignOk) {
    fail(`presign failed: status=${presignResponse.status} body=${presignResponse.body}`);
  }

  const presigned = presignResponse.json('data');
  if (!Array.isArray(presigned) || presigned.length !== imageCount) {
    fail(`presign response image count mismatch: ${presignResponse.body}`);
  }

  const uploadRequests = presigned.map((item) => ({
    method: 'PUT',
    url: item.putUrl,
    body: imageBytes,
    params: {
      headers: item.headers,
      tags: { name: 'PUT NCP presigned image' },
    },
  }));
  const uploadResponses = http.batch(uploadRequests);

  let allUploadsSucceeded = true;
  for (const response of uploadResponses) {
    objectUploadDuration.add(response.timings.duration);
    uploadedBytes.add(imageBytes.byteLength);
    if (!check(response, {
      'NCP image upload status is 2xx': (result) => result.status >= 200 && result.status < 300,
    })) {
      allUploadsSucceeded = false;
    }
  }
  if (!allUploadsSucceeded) {
    fail('one or more NCP presigned uploads failed');
  }

  const postResponse = http.post(
    `${baseUrl}/api/v1/boards/${boardId}/posts`,
    JSON.stringify({
      content: `k6 post image load test ${unique}`,
      isAnonymous: false,
      imageUrls: presigned.map((item) => item.key),
    }),
    { headers: authHeaders, tags: { name: 'POST /api/v1/boards/:boardId/posts' } },
  );
  postCreateDuration.add(postResponse.timings.duration);

  const postCreated = check(postResponse, {
    'post create status is 201': (response) => response.status === 201,
  });
  if (!postCreated) {
    console.error(`post create failed: status=${postResponse.status} body=${postResponse.body}`);
  }
}
