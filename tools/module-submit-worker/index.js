/**
 * Module submission endpoint for the ShizukuX module store.
 *
 * Accepts the multipart payload produced by `ModuleUploadClient`:
 *   - `meta`: JSON manifest (display data + claimed sha256/size)
 *   - `file`: the module ZIP
 *
 * Nothing in `meta` is trusted for the fields that matter: the id, version and hash are
 * re-derived from the ZIP itself, so a modified client cannot publish a file under another
 * module's id.
 *
 * Flow (since the review queue was added):
 *   1. `POST /submit`  — validate + rate limit, park the ZIP in `pending/<id>-<version>.zip`
 *                        and append a record to `pending/index.json`. Nothing becomes public.
 *   2. `GET  /pending` — list the queue (admin token required).
 *   3. `POST /review`  — approve (copy into `modules/` + update `modules.json`, then drop the
 *                        pending copy) or reject (drop the copy and keep an audit record).
 *
 * Environment:
 *   GITHUB_TOKEN   fine-grained PAT with contents:write on the catalog repo (secret)
 *   GITHUB_OWNER   default qianyumeng0228
 *   GITHUB_REPO    default shizukux-modules
 *   GITHUB_BRANCH  default main
 *   ADMIN_TOKEN    required for /pending and /review (secret)
 *   SUBMIT_TOKEN   optional shared secret; when set, X-Submit-Token must match
 *   SUBMIT_KV      optional KV binding used for the per-IP rate limit windows
 */

const MAX_BYTES = 32 * 1024 * 1024;
const MAX_PROP_BYTES = 64 * 1024;
const MAX_ENTRIES = 2048;
const MODULE_ID = /^[A-Za-z][A-Za-z0-9._-]{1,63}$/;

/** Smallest useful burst control: per-IP per minute / hour / day. */
const RATE_LIMITS = [
  { scope: 'minute', windowMs: 60_000, ttl: 120, limit: 3 },
  { scope: 'hour', windowMs: 3_600_000, ttl: 3_700, limit: 10 },
  { scope: 'day', windowMs: 86_400_000, ttl: 86_400, limit: 20 }
];

const PENDING_DIR = 'pending';
const PENDING_INDEX = 'pending/index.json';
/** Every submission also opens an issue in the catalog repo, so the queue has a public trace. */
const ISSUE_LABEL = 'submission';

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === 'OPTIONS') return cors(new Response(null, { status: 204 }));

    try {
      if (url.pathname === '/healthz') {
        return json({
          ok: true,
          reviewQueue: true,
          rateLimit: Boolean(env.SUBMIT_KV),
          adminConfigured: Boolean(env.ADMIN_TOKEN),
          issueSync: Boolean(env.GITHUB_TOKEN),
          issueLabel: ISSUE_LABEL
        });
      }
      if (url.pathname === '/submit') {
        if (request.method !== 'POST') return json({ ok: false, error: 'POST required' }, 405);
        return await handleSubmit(request, env);
      }
      if (url.pathname === '/pending') {
        if (request.method !== 'GET') return json({ ok: false, error: 'GET required' }, 405);
        return await handlePending(request, env);
      }
      if (url.pathname === '/index') {
        if (request.method !== 'GET') return json({ ok: false, error: 'GET required' }, 405);
        // Serving the catalog from the repo keeps the reviewer in sync; CDN copies of
        // modules.json can lag by minutes after an approval.
        const catalog = await ghGetText(env, 'modules.json');
        if (!catalog) return json({ ok: false, error: 'catalog unavailable' }, 502);
        return cors(
          new Response(catalog, {
            status: 200,
            headers: {
              'Content-Type': 'application/json; charset=utf-8',
              'Cache-Control': 'max-age=60'
            }
          })
        );
      }
      if (url.pathname === '/review') {
        if (request.method !== 'POST') return json({ ok: false, error: 'POST required' }, 405);
        return await handleReview(request, env);
      }
      if (url.pathname === '/modules') {
        if (request.method !== 'GET') return json({ ok: false, error: 'GET required' }, 405);
        return await handleModuleFiles(request, env);
      }
      if (url.pathname === '/unpublish') {
        if (request.method !== 'POST') return json({ ok: false, error: 'POST required' }, 405);
        return await handleUnpublish(request, env);
      }
      if (url.pathname === '/edit') {
        if (request.method !== 'POST') return json({ ok: false, error: 'POST required' }, 405);
        return await handleEdit(request, env);
      }
      return json({ ok: false, error: 'not found' }, 404);
    } catch (error) {
      return json({ ok: false, error: String(error && error.message ? error.message : error) }, 500);
    }
  }
};

// -- submit ---------------------------------------------------------------

async function handleSubmit(request, env) {
  if (env.SUBMIT_TOKEN && request.headers.get('X-Submit-Token') !== env.SUBMIT_TOKEN) {
    return json({ ok: false, error: 'unauthorized' }, 401);
  }

  // Rate limit before touching the body so a flood costs us almost nothing.
  const limit = await checkRateLimit(request, env);
  if (!limit.allowed) {
    return new Response(
      JSON.stringify({
        ok: false,
        error: `提交过于频繁（${limit.scope} 限额 ${limit.limit} 次），请稍后再试`,
        retryAfterSeconds: limit.retryAfterSeconds
      }),
      {
        status: 429,
        headers: corsHeaders({
          'Content-Type': 'application/json; charset=utf-8',
          'Retry-After': String(limit.retryAfterSeconds)
        })
      }
    );
  }

  let form;
  try {
    form = await request.formData();
  } catch {
    return json({ ok: false, error: 'multipart/form-data expected' }, 400);
  }

  const file = form.get('file');
  if (!(file instanceof File)) return json({ ok: false, error: 'file part missing' }, 400);
  if (file.size === 0 || file.size > MAX_BYTES) {
    return json({ ok: false, error: `zip must be 1..${MAX_BYTES} bytes` }, 400);
  }

  let meta = {};
  try {
    const metaPart = form.get('meta');
    const raw = metaPart instanceof File ? await metaPart.text() : String(metaPart || '{}');
    meta = JSON.parse(raw);
  } catch {
    return json({ ok: false, error: 'meta is not valid JSON' }, 400);
  }

  const bytes = new Uint8Array(await file.arrayBuffer());
  const sha256 = await digestHex(bytes);
  if (meta.sha256 && String(meta.sha256).toLowerCase() !== sha256) {
    return json({ ok: false, error: 'sha256 does not match the uploaded zip' }, 400);
  }

  let propText;
  try {
    propText = await readModuleProp(bytes);
  } catch (error) {
    return json({ ok: false, error: String(error.message || error) }, 400);
  }
  const props = parseProp(propText);
  const id = (props.id || '').trim();
  if (!MODULE_ID.test(id)) return json({ ok: false, error: 'invalid module id in module.prop' }, 400);

  // The upload sheet lets the author rewrite the store-facing fields before submitting, and the
  // client repacks module.prop to match; these overrides are still honoured so a client that
  // only rewrites the catalog cannot publish a label that contradicts the ZIP.
  const hasMeta = key => Object.prototype.hasOwnProperty.call(meta, key);
  const metaText = key => (hasMeta(key) ? String(meta[key] === null ? '' : meta[key]).trim() : '');

  const rawVersion = metaText('version') || (props.version || '').trim();
  if (!rawVersion) return json({ ok: false, error: 'module.prop is missing version' }, 400);
  // Catalog convention is a bare version ("1.0.3"); authors often write "v1.0.3".
  const version = rawVersion.replace(/^[vV]/, '').trim();
  if (!version) return json({ ok: false, error: 'module.prop has an empty version' }, 400);
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(version)) {
    return json({ ok: false, error: 'invalid version' }, 400);
  }

  const rawVersionCode = metaText('versionCode') || (props.versionCode || '').trim();
  const parsedCode = Number.parseInt(rawVersionCode, 10);
  const versionCode = Number.isFinite(parsedCode) && parsedCode > 0 ? parsedCode : null;

  const displayName = metaText('name') || (props.name || '').trim() || id;
  const displayAuthor = hasMeta('author')
    ? (metaText('author') || null)
    : ((props.author || '').trim() || null);
  const displayDescription = hasMeta('description')
    ? (metaText('description') || null)
    : ((props.description || '').trim() || null);

  const repoRef = repoCoordinates(env);
  if (!repoRef.token) return json({ ok: false, error: 'server is missing GITHUB_TOKEN' }, 500);

  // A published copy means this exact release already exists; a pending copy means it is
  // waiting and should not be queued twice.
  const publishedPath = `modules/${id}-${version}.zip`;
  if (await ghGetSha(env, publishedPath)) {
    return json({ ok: false, error: `${id} v${version} already published` }, 409);
  }
  const pendingPath = `${PENDING_DIR}/${id}-${version}.zip`;
  if (await ghGetSha(env, pendingPath)) {
    return json({ ok: false, error: `${id} v${version} is already waiting for review` }, 409);
  }

  const queue = await readQueue(env);
  const newer = queue.submissions.find(
    s => s.id === id && s.status === 'pending' && (s.versionCode || 0) > (versionCode || 0)
  );
  if (newer) {
    return json(
      { ok: false, error: `a newer version of ${id} is already waiting for review` },
      409
    );
  }

  const putPending = await ghPut(env, pendingPath, {
    message: `queue: hold ${id} v${version} for review`,
    content: toBase64(bytes)
  });
  if (!putPending.ok) return json({ ok: false, error: `github write failed: ${putPending.status}` }, 502);

  const ticket = makeTicket();
  const submittedAt = Math.floor(Date.now() / 1000);
  const submitterHash = (await digestHex(new TextEncoder().encode(clientIp(request)))).slice(0, 16);
  const record = {
    ticket,
    id,
    version,
    versionCode,
    name: displayName,
    author: displayAuthor,
    description: displayDescription,
    nameZh: meta.nameZh || null,
    descriptionZh: meta.descriptionZh || null,
    tags: Array.isArray(meta.tags) ? meta.tags.slice(0, 6).map(String) : [],
    sourceRepo: meta.sourceRepo || null,
    sizeBytes: file.size,
    sha256,
    pendingPath,
    submittedAt,
    submitterHash,
    status: 'pending',
    // Filled below once the public trace issue opens; null means only this queue knows.
    issueNumber: null,
    issueUrl: null
  };

  // Opens the public trace before the queue write so the record can carry its number.
  // A failure here costs the trace only, never the submission.
  const issue = await ghCreateSubmissionIssue(env, {
    ticket,
    id,
    version,
    versionCode,
    name: record.name,
    author: record.author,
    description: record.description,
    nameZh: record.nameZh,
    descriptionZh: record.descriptionZh,
    sha256,
    sizeBytes: file.size,
    pendingPath,
    submittedAt,
    submitterHash
  });
  if (issue) {
    record.issueNumber = issue.number || null;
    record.issueUrl = issue.html_url || null;
  }

  queue.submissions = queue.submissions.concat(record);
  const saved = await writeQueue(env, queue);
  if (!saved) {
    // Queue index is the source of truth for reviews; roll the orphan back.
    await ghDelete(env, pendingPath, `queue: rollback ${id} v${version}`);
    return json({ ok: false, error: 'failed to record the submission, please retry' }, 502);
  }

  return json(
    {
      ok: true,
      status: 'pending',
      ticket,
      message: `${id} v${version} 已进入审核队列`,
      sha256,
      sizeBytes: file.size,
      issueNumber: record.issueNumber,
      issueUrl: record.issueUrl
    },
    202
  );
}

// -- review queue ---------------------------------------------------------

async function handlePending(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);
  const queue = await readQueue(env);
  const pending = queue.submissions.filter(s => s.status === 'pending');
  return json({ ok: true, count: pending.length, submissions: pending });
}

async function handleReview(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: 'JSON body expected' }, 400);
  }
  const action = String(body.action || '').toLowerCase();
  if (action !== 'approve' && action !== 'reject') {
    return json({ ok: false, error: "action must be 'approve' or 'reject'" }, 400);
  }
  const ticket = String(body.ticket || '').trim();
  if (!ticket && !(body.id && body.version)) {
    return json({ ok: false, error: 'ticket or id+version required' }, 400);
  }

  let queue = await readQueue(env);
  const record = queue.submissions.find(
    s => (ticket ? s.ticket === ticket : s.id === body.id && s.version === body.version) &&
      s.status === 'pending'
  );
  if (!record) return json({ ok: false, error: 'no pending submission matched' }, 404);

  if (action === 'reject') {
    record.status = 'rejected';
    record.reviewedAt = Math.floor(Date.now() / 1000);
    record.reason = String(body.reason || '').slice(0, 200) || null;
    const saved = await writeQueue(env, queue);
    if (!saved) return json({ ok: false, error: 'failed to update the queue' }, 502);
    await ghDelete(env, record.pendingPath, `queue: reject ${record.id} v${record.version}`);
    const issueClosed = await ghSettleIssue(env, record, {
      approved: false,
      reason: record.reason,
      publishedPath: null
    });
    return json({ ok: true, status: 'rejected', ticket: record.ticket, issueClosed });
  }

  // approve: re-read the parked ZIP so the published bytes are exactly the reviewed ones.
  const parked = await ghGetBase64(env, record.pendingPath);
  if (!parked) return json({ ok: false, error: 'pending zip is missing, cannot approve' }, 410);
  const bytes = base64ToBytes(parked);
  const sha256 = await digestHex(bytes);
  if (sha256 !== record.sha256) {
    return json({ ok: false, error: 'pending zip hash changed since submission' }, 409);
  }

  const props = parseProp(await readModuleProp(bytes));
  const publishedPath = `modules/${record.id}-${record.version}.zip`;
  if (await ghGetSha(env, publishedPath)) {
    return json({ ok: false, error: `${record.id} v${record.version} already published` }, 409);
  }

  const putZip = await ghPut(env, publishedPath, {
    message: `module: publish ${record.id} v${record.version}`,
    content: parked
  });
  if (!putZip.ok) return json({ ok: false, error: `github write failed: ${putZip.status}` }, 502);

  const entry = {
    id: record.id,
    name: record.name,
    version: record.version,
    versionCode: record.versionCode,
    author: record.author,
    description: record.description,
    nameZh: record.nameZh,
    descriptionZh: record.descriptionZh,
    downloadUrl: `https://cdn.jsdelivr.net/gh/${repoCoordinates(env).owner}/${repoCoordinates(env).repo}@${repoCoordinates(env).branch}/${publishedPath}`,
    fallbackUrl: `https://raw.githubusercontent.com/${repoCoordinates(env).owner}/${repoCoordinates(env).repo}/${repoCoordinates(env).branch}/${publishedPath}`,
    sizeBytes: record.sizeBytes,
    sha256: record.sha256,
    minSdk: Number.parseInt(props.minSdk || '0', 10) || null,
    hasAction: hasEntry(bytes, 'action.sh'),
    hasService: hasEntry(bytes, 'service.sh'),
    usesWebUI: hasEntry(bytes, 'index.html') || hasEntry(bytes, 'webroot/index.html'),
    declaresShellBridge: String(props.usesShellBridge).toLowerCase() === 'true',
    tags: record.tags || [],
    lastUpdated: Math.floor(Date.now() / 1000),
    sourceRepo: record.sourceRepo
  };

  const indexSaved = await mergeIntoIndex(env, record.id, entry);
  if (!indexSaved.ok) {
    return json({ ok: false, error: `zip published but index update failed: ${indexSaved.error}` }, 502);
  }

  record.status = 'approved';
  record.reviewedAt = Math.floor(Date.now() / 1000);
  record.reason = String(body.reason || '').slice(0, 200) || null;
  const saved = await writeQueue(env, queue);
  if (!saved) {
    // The module is already live; a stale queue record is recoverable, report it plainly.
    return json({ ok: true, status: 'approved', warning: 'queue record not updated' });
  }
  await ghDelete(env, record.pendingPath, `queue: release ${record.id} v${record.version}`);
  const issueClosed = await ghSettleIssue(env, record, {
    approved: true,
    reason: record.reason,
    publishedPath
  });

  return json({ ok: true, status: 'approved', ticket: record.ticket, publishedPath, issueClosed });
}

// -- published module management ------------------------------------------

/**
 * Lists every ZIP under modules/. Ids come from the catalog first; a file the catalog no
 * longer mentions still resolves through its own `<id>-<version>.zip` name so it stays
 * manageable instead of showing up as an ownerless orphan.
 */
async function handleModuleFiles(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);
  const listing = await ghListDir(env, 'modules');
  if (!Array.isArray(listing)) return json({ ok: false, error: 'modules directory unavailable' }, 502);

  const indexDoc = (await ghGetJson(env, 'modules.json')) || {};
  const ids = (Array.isArray(indexDoc.modules) ? indexDoc.modules : []).map(m => m.id);
  const files = [];
  for (const item of listing) {
    if (item.type !== 'file' || !/\.zip$/i.test(item.name)) continue;
    // Longest id wins, so "a.b" is not mistaken for the prefix of "a.bc-1.0.zip".
    const owner = ids
      .filter(candidate => item.name.startsWith(`${candidate}-`))
      .sort((a, b) => b.length - a.length)[0] || null;
    const derived = owner ? null : deriveModuleFromFileName(item.name);
    const id = owner || (derived ? derived.id : null);
    const version = owner
      ? item.name.slice(owner.length + 1).replace(/\.zip$/i, '')
      : (derived ? derived.version : null);
    files.push({
      name: item.name,
      id,
      version,
      sizeBytes: item.size,
      inCatalog: Boolean(owner)
    });
  }
  return json({ ok: true, count: files.length, files });
}

/** Splits `shizukux.recorder-1.0.3.zip` into its id and version when the catalog is silent. */
function deriveModuleFromFileName(name) {
  const stem = name.replace(/\.zip$/i, '');
  const cut = stem.lastIndexOf('-');
  if (cut <= 0) return null;
  const id = stem.slice(0, cut);
  const version = stem.slice(cut + 1);
  if (!/^[A-Za-z][A-Za-z0-9._-]{1,63}$/.test(id)) return null;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(version)) return null;
  return { id, version };
}

/**
 * Removes a published module, or one of its versions.
 * `{id}`           → drop the catalog entry and every `modules/<id>-*.zip`
 * `{id, version}`  → delete that ZIP only; deleting the live version steps the entry back to
 *                    the newest ZIP left in the repo, and drops the entry only when none is left
 */
async function handleUnpublish(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: 'JSON body expected' }, 400);
  }

  const id = String(body.id || '').trim();
  if (!/^[A-Za-z][A-Za-z0-9._-]{1,63}$/.test(id)) {
    return json({ ok: false, error: 'invalid module id' }, 400);
  }
  const version = String(body.version || '').trim().replace(/^[vV]/, '');
  const reason = String(body.reason || '').slice(0, 200) || null;

  const indexDoc = (await ghGetJson(env, 'modules.json')) || {};
  const modules = Array.isArray(indexDoc.modules) ? indexDoc.modules : [];
  const current = modules.find(m => m.id === id);

  if (version) {
    const fileRemoved = await ghDelete(
      env,
      `modules/${id}-${version}.zip`,
      `module: unpublish ${id} v${version}`
    );

    // Deleting a history version must not touch the catalog entry at all.
    if (!current || String(current.version) !== version) {
      if (!fileRemoved) return json({ ok: false, error: 'version not found' }, 404);
      return json({ ok: true, id, version, fileRemoved, indexAction: 'kept', reason });
    }

    // Deleting the live version steps the entry back to the newest ZIP still in the repo,
    // so the module keeps its listing instead of vanishing from the store.
    const remaining = (await listModuleFiles(env, id))
      .map(name => name.slice(id.length + 1).replace(/\.zip$/i, ''))
      .filter(candidate => candidate !== version);
    const fallbackVersion = remaining.sort(compareVersions).pop() || null;

    if (fallbackVersion) {
      const entry = await describeCatalogVersion(env, id, fallbackVersion, current);
      if (!entry) {
        return json({ ok: false, error: `file removed but ${fallbackVersion} could not be read` }, 502);
      }
      const result = await replaceIndexEntry(env, id, entry);
      if (!result.ok) {
        return json({ ok: false, error: `file removed but index rollback failed: ${result.error}` }, 502);
      }
      return json({
        ok: true,
        id,
        version,
        fileRemoved,
        indexAction: 'rolledBack',
        rolledBackTo: fallbackVersion,
        reason
      });
    }

    const result = await removeFromIndex(env, id);
    if (!result.ok) {
      return json({ ok: false, error: `file removed but index update failed: ${result.error}` }, 502);
    }
    return json({ ok: true, id, version, fileRemoved, indexAction: 'removed', reason });
  }

  const files = await listModuleFiles(env, id);
  let deletedFiles = 0;
  for (const name of files) {
    if (await ghDelete(env, `modules/${name}`, `module: remove ${id} ${name}`)) deletedFiles += 1;
  }

  let indexRemoved = false;
  if (current) {
    const result = await removeFromIndex(env, id);
    if (!result.ok) {
      return json({ ok: false, error: `files removed but index update failed: ${result.error}` }, 502);
    }
    indexRemoved = true;
  }
  if (deletedFiles === 0 && !indexRemoved) return json({ ok: false, error: 'module not found' }, 404);
  return json({ ok: true, id, deletedFiles, indexRemoved, reason });
}

/** Fields an operator may rewrite without touching the ZIP itself. */
const EDITABLE_TEXT_FIELDS = ['name', 'nameZh', 'description', 'descriptionZh', 'author', 'sourceRepo'];

function repoFileUrls(env, path) {
  const { owner, repo, branch } = repoCoordinates(env);
  return {
    downloadUrl: `https://cdn.jsdelivr.net/gh/${owner}/${repo}@${branch}/${path}`,
    fallbackUrl: `https://raw.githubusercontent.com/${owner}/${repo}/${branch}/${path}`
  };
}

/**
 * Rewrites a published catalog entry.
 * Display fields (name/author/descriptions/tags) are free-form; changing `version` also renames
 * the ZIP so `downloadUrl` keeps pointing at a real file, and `versionCode` follows it.
 */
async function handleEdit(request, env) {
  if (!isAdmin(request, env)) return json({ ok: false, error: 'unauthorized' }, 401);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ ok: false, error: 'JSON body expected' }, 400);
  }
  const id = String(body.id || '').trim();
  if (!/^[A-Za-z][A-Za-z0-9._-]{1,63}$/.test(id)) {
    return json({ ok: false, error: 'invalid module id' }, 400);
  }
  const patch = body.patch && typeof body.patch === 'object' ? body.patch : null;
  if (!patch) return json({ ok: false, error: 'patch object required' }, 400);

  const expectedVersion = String(body.version || '').trim().replace(/^[vV]/, '');

  for (let attempt = 0; attempt < 3; attempt += 1) {
    const doc = (await ghGetJson(env, 'modules.json')) || {};
    const modules = Array.isArray(doc.modules) ? doc.modules : [];
    const position = modules.findIndex(m => m.id === id);
    if (position < 0) return json({ ok: false, error: 'module not found' }, 404);

    const entry = { ...modules[position] };
    if (expectedVersion && String(entry.version) !== expectedVersion) {
      return json({ ok: false, error: `catalog holds v${entry.version}, not v${expectedVersion}` }, 409);
    }

    let renamedFrom = null;
    let renamedTo = null;

    if (patch.version !== undefined && String(patch.version).trim()) {
      const nextVersion = String(patch.version).trim().replace(/^[vV]/, '');
      if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$/.test(nextVersion)) {
        return json({ ok: false, error: 'invalid version' }, 400);
      }
      if (nextVersion !== String(entry.version)) {
        const from = `modules/${id}-${entry.version}.zip`;
        const to = `modules/${id}-${nextVersion}.zip`;
        if (await ghGetSha(env, to)) {
          return json({ ok: false, error: `v${nextVersion} already exists in the repo` }, 409);
        }
        const content = await ghGetBase64(env, from);
        if (!content) return json({ ok: false, error: 'published zip is missing' }, 404);
        const put = await ghPut(env, to, {
          message: `module: rename ${id} v${entry.version} -> v${nextVersion}`,
          content
        });
        if (!put.ok) return json({ ok: false, error: `rename failed: ${put.status}` }, 502);
        await ghDelete(env, from, `module: drop renamed copy of ${id} v${entry.version}`);
        entry.version = nextVersion;
        Object.assign(entry, repoFileUrls(env, to));
        renamedFrom = from;
        renamedTo = to;
      }
    }

    if (patch.versionCode !== undefined && String(patch.versionCode).trim() !== '') {
      const code = Number.parseInt(String(patch.versionCode), 10);
      if (!Number.isFinite(code) || code <= 0) {
        return json({ ok: false, error: 'invalid versionCode' }, 400);
      }
      entry.versionCode = code;
    }

    for (const field of EDITABLE_TEXT_FIELDS) {
      if (patch[field] === undefined) continue;
      const limit = field === 'description' || field === 'descriptionZh' ? 400 : 120;
      const value = String(patch[field] ?? '').trim().slice(0, limit);
      if (value) entry[field] = value;
      else delete entry[field];
    }

    if (patch.tags !== undefined) {
      const raw = Array.isArray(patch.tags) ? patch.tags : String(patch.tags).split(',');
      entry.tags = raw
        .map(tag => String(tag).trim().slice(0, 24))
        .filter(Boolean)
        .slice(0, 6);
    }

    entry.lastUpdated = Math.floor(Date.now() / 1000);
    const next = {
      ...doc,
      schemaVersion: doc.schemaVersion || 1,
      updatedAt: Math.floor(Date.now() / 1000),
      modules: modules.map((m, i) => (i === position ? entry : m))
    };
    const sha = await ghGetSha(env, 'modules.json');
    const result = await ghPut(env, 'modules.json', {
      message: `module: edit ${id} v${entry.version}`,
      content: toBase64(new TextEncoder().encode(JSON.stringify(next, null, 2)))
    }, sha);
    if (result.ok) return json({ ok: true, module: entry, renamedFrom, renamedTo });
    if (result.status !== 409 && result.status !== 422) {
      return json({ ok: false, error: `index update failed: ${result.status}` }, 502);
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return json({ ok: false, error: 'index is contended, retry later' }, 503);
}

/** Dotted version comparison; positive means `a` is newer than `b`. */
function compareVersions(a, b) {
  const left = String(a || '').split('.');
  const right = String(b || '').split('.');
  const length = Math.max(left.length, right.length);
  for (let index = 0; index < length; index += 1) {
    const l = left[index] === undefined ? '0' : left[index];
    const r = right[index] === undefined ? '0' : right[index];
    if (/^\d+$/.test(l) && /^\d+$/.test(r)) {
      const diff = Number(l) - Number(r);
      if (diff !== 0) return diff;
      continue;
    }
    if (l !== r) return l > r ? 1 : -1;
  }
  return 0;
}

async function listModuleFiles(env, id) {
  const listing = await ghListDir(env, 'modules');
  if (!Array.isArray(listing)) return [];
  return listing
    .filter(item => item.type === 'file' && item.name.startsWith(`${id}-`) && /\.zip$/i.test(item.name))
    .map(item => item.name);
}

/**
 * Rebuilds a catalog entry for `version` from the ZIP that is actually in the repo, keeping
 * the descriptive fields of `base`.
 */
async function describeCatalogVersion(env, id, version, base) {
  const path = `modules/${id}-${version}.zip`;
  const base64 = await ghGetBase64(env, path);
  if (!base64) return null;
  const bytes = base64ToBytes(base64);
  const { downloadUrl, fallbackUrl } = repoFileUrls(env, path);
  return {
    ...base,
    id,
    version,
    sizeBytes: bytes.byteLength,
    sha256: await digestHex(bytes),
    downloadUrl,
    fallbackUrl,
    lastUpdated: Math.floor(Date.now() / 1000)
  };
}

/** Writes `entry` over the catalog entry for `id`, deliberately skipping the versionCode guard. */
async function replaceIndexEntry(env, id, entry, attempts = 3) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const indexDoc = (await ghGetJson(env, 'modules.json')) || {
      name: 'ShizukuX 官方模块仓库',
      schemaVersion: 1,
      modules: []
    };
    const modules = Array.isArray(indexDoc.modules) ? indexDoc.modules : [];
    const next = {
      ...indexDoc,
      schemaVersion: indexDoc.schemaVersion || 1,
      updatedAt: Math.floor(Date.now() / 1000),
      modules: modules.filter(m => m.id !== id).concat(entry)
    };
    const payload = new TextEncoder().encode(JSON.stringify(next, null, 2));
    const sha = await ghGetSha(env, 'modules.json');
    const result = await ghPut(env, 'modules.json', {
      message: `module: roll back ${id} to v${entry.version}`,
      content: toBase64(payload)
    }, sha);
    if (result.ok) return { ok: true };
    if (result.status !== 409 && result.status !== 422) {
      return { ok: false, error: String(result.status) };
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return { ok: false, error: 'index is contended, retry later' };
}

async function removeFromIndex(env, id, attempts = 3) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const indexDoc = (await ghGetJson(env, 'modules.json')) || {};
    const modules = Array.isArray(indexDoc.modules) ? indexDoc.modules : [];
    if (!modules.some(m => m.id === id)) return { ok: true, removed: false };
    const next = {
      ...indexDoc,
      schemaVersion: indexDoc.schemaVersion || 1,
      updatedAt: Math.floor(Date.now() / 1000),
      modules: modules.filter(m => m.id !== id)
    };
    const payload = new TextEncoder().encode(JSON.stringify(next, null, 2));
    const sha = await ghGetSha(env, 'modules.json');
    const result = await ghPut(env, 'modules.json', {
      message: `module: unpublish ${id}`,
      content: toBase64(payload)
    }, sha);
    if (result.ok) return { ok: true, removed: true };
    if (result.status !== 409 && result.status !== 422) {
      return { ok: false, error: String(result.status) };
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return { ok: false, error: 'index is contended, retry later' };
}

// -- queue persistence ----------------------------------------------------

async function readQueue(env) {
  const doc = await ghGetJson(env, PENDING_INDEX);
  if (doc && Array.isArray(doc.submissions)) {
    return { schemaVersion: doc.schemaVersion || 1, submissions: doc.submissions };
  }
  return { schemaVersion: 1, submissions: [] };
}

async function writeQueue(env, queue, attempts = 3) {
  const body = {
    schemaVersion: 1,
    updatedAt: Math.floor(Date.now() / 1000),
    // Keep the newest 200 records so the file cannot grow without bound.
    submissions: queue.submissions.slice(-200)
  };
  const payload = new TextEncoder().encode(JSON.stringify(body, null, 2));
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const sha = await ghGetSha(env, PENDING_INDEX);
    const result = await ghPut(env, PENDING_INDEX, {
      message: `queue: update submission index`,
      content: toBase64(payload)
    }, sha);
    if (result.ok) return true;
    if (result.status !== 409 && result.status !== 422) return false;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return false;
}

async function mergeIntoIndex(env, id, entry, attempts = 3) {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const indexDoc = (await ghGetJson(env, 'modules.json')) || {
      name: 'ShizukuX 官方模块仓库',
      schemaVersion: 1,
      modules: []
    };
    const modules = Array.isArray(indexDoc.modules) ? indexDoc.modules : [];
    const previous = modules.find(m => m.id === id);
    if (previous && (previous.versionCode || 0) > (entry.versionCode || 0)) {
      return { ok: false, error: 'a newer version is already in the catalog' };
    }
    const next = {
      ...indexDoc,
      schemaVersion: indexDoc.schemaVersion || 1,
      updatedAt: Math.floor(Date.now() / 1000),
      modules: modules.filter(m => m.id !== id).concat(entry)
    };
    const payload = new TextEncoder().encode(JSON.stringify(next, null, 2));
    const sha = await ghGetSha(env, 'modules.json');
    const result = await ghPut(env, 'modules.json', {
      message: `module: publish ${id} v${entry.version}`,
      content: toBase64(payload)
    }, sha);
    if (result.ok) return { ok: true };
    if (result.status !== 409 && result.status !== 422) {
      return { ok: false, error: String(result.status) };
    }
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  return { ok: false, error: 'index is contended, retry later' };
}

// -- rate limiting --------------------------------------------------------

async function checkRateLimit(request, env) {
  const now = Date.now();
  const ip = clientIp(request);
  const identity = await digestHex(new TextEncoder().encode(ip));

  // Without KV the endpoint still works, just without throttling; say so in /healthz.
  if (!env.SUBMIT_KV) return { allowed: true };

  const hits = [];
  for (const rule of RATE_LIMITS) {
    const bucket = Math.floor(now / rule.windowMs);
    const key = `rl:${identity.slice(0, 24)}:${rule.scope}:${bucket}`;
    const current = Number.parseInt((await env.SUBMIT_KV.get(key)) || '0', 10);
    hits.push({ rule, key, current });
    if (current >= rule.limit) {
      return {
        allowed: false,
        scope: rule.scope,
        limit: rule.limit,
        retryAfterSeconds: Math.ceil((rule.windowMs - (now % rule.windowMs)) / 1000)
      };
    }
  }
  for (const hit of hits) {
    await env.SUBMIT_KV.put(hit.key, String(hit.current + 1), { expirationTtl: hit.rule.ttl });
  }
  return { allowed: true };
}

function clientIp(request) {
  return request.headers.get('CF-Connecting-IP') || 'unknown';
}

// -- GitHub helpers -------------------------------------------------------

function repoCoordinates(env) {
  return {
    owner: env.GITHUB_OWNER || 'qianyumeng0228',
    repo: env.GITHUB_REPO || 'shizukux-modules',
    branch: env.GITHUB_BRANCH || 'main',
    token: env.GITHUB_TOKEN
  };
}

function ghHeaders(env) {
  return {
    Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    Accept: 'application/vnd.github+json',
    'User-Agent': 'ShizukuX-ModuleSubmit/1.1'
  };
}

function contentsUrl(env, path) {
  const { owner, repo } = repoCoordinates(env);
  return `https://api.github.com/repos/${owner}/${repo}/contents/${path}`;
}

async function ghGetSha(env, path) {
  const { branch } = repoCoordinates(env);
  const response = await fetch(`${contentsUrl(env, path)}?ref=${encodeURIComponent(branch)}`, {
    headers: ghHeaders(env)
  });
  if (!response.ok) return null;
  const body = await response.json();
  return body.sha || null;
}

async function ghGetJson(env, path) {
  const text = await ghGetText(env, path);
  if (text === null) return null;
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

/** Directory listings answer with a bare array, so they cannot go through ghGetText. */
async function ghListDir(env, path) {
  const { branch } = repoCoordinates(env);
  const response = await fetch(`${contentsUrl(env, path)}?ref=${encodeURIComponent(branch)}`, {
    headers: ghHeaders(env)
  });
  if (!response.ok) return null;
  const body = await response.json();
  return Array.isArray(body) ? body : null;
}

async function ghGetText(env, path) {
  const { branch } = repoCoordinates(env);
  const response = await fetch(`${contentsUrl(env, path)}?ref=${encodeURIComponent(branch)}`, {
    headers: ghHeaders(env)
  });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`github read failed: ${response.status}`);
  const body = await response.json();
  if (!body.content) return null;
  return new TextDecoder().decode(base64ToBytes(body.content.replace(/\n/g, '')));
}

async function ghGetBase64(env, path) {
  const { branch } = repoCoordinates(env);
  const response = await fetch(`${contentsUrl(env, path)}?ref=${encodeURIComponent(branch)}`, {
    headers: ghHeaders(env)
  });
  if (!response.ok) return null;
  const body = await response.json();
  return body.content ? body.content.replace(/\n/g, '') : null;
}

async function ghPut(env, path, payload, sha) {
  const { branch } = repoCoordinates(env);
  return fetch(contentsUrl(env, path), {
    method: 'PUT',
    headers: { ...ghHeaders(env), 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...payload, branch, ...(sha ? { sha } : {}) })
  });
}

async function ghDelete(env, path, message) {
  const sha = await ghGetSha(env, path);
  if (!sha) return false;
  const { branch } = repoCoordinates(env);
  const response = await fetch(contentsUrl(env, path), {
    method: 'DELETE',
    headers: { ...ghHeaders(env), 'Content-Type': 'application/json' },
    body: JSON.stringify({ message, sha, branch })
  });
  return response.ok;
}

// -- public trace issues --------------------------------------------------

function issueUrlFor(env, number) {
  const { owner, repo } = repoCoordinates(env);
  return `https://api.github.com/repos/${owner}/${repo}/issues/${number}`;
}

async function ghCreateSubmissionIssue(env, record) {
  const { token, owner, repo } = repoCoordinates(env);
  if (!token) return null;

  const payload = {
    ticket: record.ticket,
    id: record.id,
    version: record.version,
    versionCode: record.versionCode,
    name: record.name,
    author: record.author,
    description: record.description,
    nameZh: record.nameZh,
    descriptionZh: record.descriptionZh,
    sha256: record.sha256,
    sizeBytes: record.sizeBytes,
    pendingPath: record.pendingPath,
    submittedAt: record.submittedAt,
    submitterHash: record.submitterHash
  };

  const body = [
    `模块 \`${record.id}\` v${record.version} 已进入审核队列，等待管理员处理。`,
    '',
    '| 项目 | 值 |',
    '| --- | --- |',
    `| 名称 | ${record.name || record.id} |`,
    `| 作者 | ${record.author || '—'} |`,
    `| 版本 | v${record.version}（code ${record.versionCode ?? '—'}） |`,
    `| 大小 | ${record.sizeBytes} B |`,
    `| SHA-256 | \`${record.sha256}\` |`,
    `| 队列 ticket | \`${record.ticket}\` |`,
    '',
    '```json',
    JSON.stringify(payload, null, 2),
    '```'
  ].join('\n');

  const create = labels =>
    fetch(`https://api.github.com/repos/${owner}/${repo}/issues`, {
      method: 'POST',
      headers: { ...ghHeaders(env), 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: `[提交] ${record.name || record.id} v${record.version}`,
        body,
        ...(labels ? { labels: [ISSUE_LABEL] } : {})
      })
    });

  // The label may not exist yet in a fresh catalog repo; a 422 must not lose the trace.
  let response = await create(true);
  if (response.status === 422) response = await create(false);
  if (!response.ok) return null;

  const created = await response.json();
  return { number: created.number, html_url: created.html_url };
}

async function ghCommentIssue(env, number, text) {
  const { token, owner, repo } = repoCoordinates(env);
  if (!token || !number) return false;
  const response = await fetch(`${issueUrlFor(env, number)}/comments`, {
    method: 'POST',
    headers: { ...ghHeaders(env), 'Content-Type': 'application/json' },
    body: JSON.stringify({ body: text })
  });
  return response.ok;
}

async function ghCloseIssue(env, number) {
  const { owner, repo, token } = repoCoordinates(env);
  if (!token || !number) return false;
  const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/issues/${number}`, {
    method: 'PATCH',
    headers: { ...ghHeaders(env), 'Content-Type': 'application/json' },
    body: JSON.stringify({ state: 'closed', state_reason: 'completed' })
  });
  return response.ok;
}

/** Comments the verdict on the trace issue and closes it. Never blocks the review itself. */
async function ghSettleIssue(env, record, verdict) {
  if (!record.issueNumber) return false;
  const { owner, repo, branch } = repoCoordinates(env);
  const lines = [];
  if (verdict.approved) {
    lines.push(`已通过，模块已上架：\`${verdict.publishedPath}\``);
    lines.push(`下载地址：https://cdn.jsdelivr.net/gh/${owner}/${repo}@${branch}/${verdict.publishedPath}`);
  } else {
    lines.push('已拒绝，未上架。');
    if (verdict.reason) lines.push(`原因：${verdict.reason}`);
  }
  await ghCommentIssue(env, record.issueNumber, lines.join('\n'));
  return ghCloseIssue(env, record.issueNumber);
}

// -- ZIP / module.prop ----------------------------------------------------

/** Walks the central directory and returns the text of `module.prop`. */
async function readModuleProp(bytes) {
  const eocd = findSignature(bytes, 0x06054b50);
  if (eocd < 0) throw new Error('not a zip archive');
  const total = readU16(bytes, eocd + 10);
  const cdOffset = readU32(bytes, eocd + 16);
  if (total > MAX_ENTRIES) throw new Error(`too many entries (max ${MAX_ENTRIES})`);

  let cursor = cdOffset;
  for (let i = 0; i < total; i++) {
    if (readU32(bytes, cursor) !== 0x02014b50) throw new Error('corrupt central directory');
    const method = readU16(bytes, cursor + 10);
    const compSize = readU32(bytes, cursor + 20);
    const nameLen = readU16(bytes, cursor + 28);
    const extraLen = readU16(bytes, cursor + 30);
    const commentLen = readU16(bytes, cursor + 32);
    const localOffset = readU32(bytes, cursor + 42);
    const name = new TextDecoder().decode(bytes.subarray(cursor + 46, cursor + 46 + nameLen));
    if (!name.endsWith('/') && (name === 'module.prop' || name.endsWith('/module.prop'))) {
      return await extractEntry(bytes, localOffset, method, compSize);
    }
    cursor += 46 + nameLen + extraLen + commentLen;
  }
  throw new Error('module.prop is missing from the zip');
}

function hasEntry(bytes, wanted) {
  const eocd = findSignature(bytes, 0x06054b50);
  if (eocd < 0) return false;
  const total = readU16(bytes, eocd + 10);
  let cursor = readU32(bytes, eocd + 16);
  for (let i = 0; i < total; i++) {
    if (readU32(bytes, cursor) !== 0x02014b50) return false;
    const nameLen = readU16(bytes, cursor + 28);
    const extraLen = readU16(bytes, cursor + 30);
    const commentLen = readU16(bytes, cursor + 32);
    const name = new TextDecoder().decode(bytes.subarray(cursor + 46, cursor + 46 + nameLen));
    if (name === wanted || name.endsWith(`/${wanted}`)) return true;
    cursor += 46 + nameLen + extraLen + commentLen;
  }
  return false;
}

async function extractEntry(bytes, localOffset, method, compSize) {
  if (readU32(bytes, localOffset) !== 0x04034b50) throw new Error('corrupt local header');
  const nameLen = readU16(bytes, localOffset + 26);
  const extraLen = readU16(bytes, localOffset + 28);
  const start = localOffset + 30 + nameLen + extraLen;
  const payload = bytes.subarray(start, start + compSize);

  let raw;
  if (method === 0) {
    raw = payload;
  } else if (method === 8) {
    const stream = new Blob([payload]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    raw = new Uint8Array(await new Response(stream).arrayBuffer());
  } else {
    throw new Error(`unsupported compression method ${method}`);
  }
  if (raw.byteLength > MAX_PROP_BYTES) throw new Error('module.prop is too large');
  return new TextDecoder().decode(raw);
}

function findSignature(bytes, signature) {
  for (let i = bytes.length - 22; i >= 0; i--) {
    if (readU32(bytes, i) === signature) return i;
  }
  return -1;
}

function readU16(bytes, offset) {
  return bytes[offset] | (bytes[offset + 1] << 8);
}

function readU32(bytes, offset) {
  return (
    bytes[offset] |
    (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16) |
    (bytes[offset + 3] << 24)
  ) >>> 0;
}

// -- misc ----------------------------------------------------------------

function parseProp(raw) {
  const out = {};
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#') || !trimmed.includes('=')) continue;
    const index = trimmed.indexOf('=');
    out[trimmed.slice(0, index).trim()] = trimmed.slice(index + 1).trim();
  }
  return out;
}

function makeTicket() {
  const time = Date.now().toString(36);
  const random = crypto.getRandomValues(new Uint8Array(4));
  const suffix = [...random].map(b => b.toString(16).padStart(2, '0')).join('');
  return `t_${time}_${suffix}`;
}

function isAdmin(request, env) {
  if (!env.ADMIN_TOKEN) return false;
  const given = request.headers.get('X-Admin-Token') || '';
  if (given.length !== env.ADMIN_TOKEN.length) return false;
  let diff = 0;
  for (let i = 0; i < given.length; i += 1) {
    diff |= given.charCodeAt(i) ^ env.ADMIN_TOKEN.charCodeAt(i);
  }
  return diff === 0;
}

async function digestHex(bytes) {
  const buffer = await crypto.subtle.digest('SHA-256', bytes);
  return [...new Uint8Array(buffer)].map(b => b.toString(16).padStart(2, '0')).join('');
}

function toBase64(bytes) {
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function base64ToBytes(base64) {
  const binary = atob(base64);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
  return out;
}

function corsHeaders(extra = {}) {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type, X-Submit-Token, X-Admin-Token',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    ...extra
  };
}

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: corsHeaders({ 'Content-Type': 'application/json; charset=utf-8' })
  });
}

function cors(response) {
  for (const [key, value] of Object.entries(corsHeaders())) response.headers.set(key, value);
  return response;
}
