import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const imageCount = Number(__ENV.IMAGE_COUNT || 5);
const uploadBatchSize = Number(__ENV.UPLOAD_BATCH_SIZE || imageCount);
const vus = Number(__ENV.VUS || 1);
const iterations = Number(__ENV.ITERATIONS || 1);
const imageFilePath = __ENV.IMAGE_FILE;
const tokens = (__ENV.ACCESS_TOKENS || __ENV.ACCESS_TOKEN || '')
  .split(',')
  .map((token) => token.trim())
  .filter(Boolean);

if (!imageFilePath) fail('IMAGE_FILE is required');
if (tokens.length === 0) fail('ACCESS_TOKEN or ACCESS_TOKENS is required');
if (imageCount < 1 || imageCount > 5) fail('IMAGE_COUNT must be between 1 and 5');
if (uploadBatchSize < 1 || uploadBatchSize > imageCount) {
  fail('UPLOAD_BATCH_SIZE must be between 1 and IMAGE_COUNT');
}
if (tokens.length < vus) {
  fail(`ACCESS_TOKENS requires at least ${vus} tokens because each VU must use a separate test user`);
}

const imageBytes = open(imageFilePath, 'b');
const presignDuration = new Trend('upload_only_presign_duration', true);
const objectUploadDuration = new Trend('upload_only_object_upload_duration', true);
const flowDuration = new Trend('upload_only_flow_duration', true);
const uploadedBytes = new Counter('upload_only_uploaded_bytes');

export const options = {
  scenarios: {
    post_image_upload_only: {
      executor: 'per-vu-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '10m',
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    upload_only_presign_duration: ['p(95)<1000'],
    upload_only_object_upload_duration: ['p(95)<10000'],
  },
};

export default function () {
  const startedAt = Date.now();
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
      uploadSessionId: `k6-upload-only-${unique}`,
      files: Array.from({ length: imageCount }, (_, index) => ({
        filename: `k6-upload-only-${unique}-${index}.jpg`,
        contentType: 'image/jpeg',
      })),
    }),
    { headers: authHeaders, tags: { name: 'POST /api/v1/images/presign upload-only' } },
  );
  presignDuration.add(presignResponse.timings.duration);

  if (!check(presignResponse, {
    'upload-only presign status is 200': (response) => response.status === 200,
  })) {
    fail(`presign failed: status=${presignResponse.status} body=${presignResponse.body}`);
  }

  const presigned = presignResponse.json('data');
  if (!Array.isArray(presigned) || presigned.length !== imageCount) {
    fail(`presign response image count mismatch: ${presignResponse.body}`);
  }

  let allUploadsSucceeded = true;
  for (let offset = 0; offset < presigned.length; offset += uploadBatchSize) {
    const uploadRequests = presigned.slice(offset, offset + uploadBatchSize).map((item) => ({
      method: 'PUT',
      url: item.putUrl,
      body: imageBytes,
      params: {
        headers: item.headers,
        tags: { name: 'PUT NCP presigned image upload-only' },
      },
    }));

    for (const response of http.batch(uploadRequests)) {
      objectUploadDuration.add(response.timings.duration);
      uploadedBytes.add(imageBytes.byteLength);
      if (!check(response, {
        'upload-only NCP image upload status is 2xx': (result) => result.status >= 200 && result.status < 300,
      })) {
        allUploadsSucceeded = false;
      }
    }
  }

  flowDuration.add(Date.now() - startedAt);
  if (!allUploadsSucceeded) {
    fail('one or more upload-only NCP presigned uploads failed');
  }
}
