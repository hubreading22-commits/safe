export default {
    async fetch(request, env, ctx) {
        const url = new URL(request.url);
        const method = request.method;

        const corsHeaders = {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type',
        };

        if (method === 'OPTIONS') {
            return new Response(null, { headers: corsHeaders });
        }

        // ── Safe KV helpers ──
        async function kvGet(key, defaultValue = null) {
            try {
                if (!env.SAFEBROWSER_KV) return defaultValue;
                const val = await env.SAFEBROWSER_KV.get(key);
                return val !== null ? val : defaultValue;
            } catch (e) {
                console.error('KV get error:', e.message);
                return defaultValue;
            }
        }

        async function kvPut(key, value) {
            try {
                if (!env.SAFEBROWSER_KV) return false;
                await env.SAFEBROWSER_KV.put(key, value);
                return true;
            } catch (e) {
                console.error('KV put error:', e.message);
                return false;
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 1) POST /api/domains
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/domains' && method === 'POST') {
            try {
                const body = await request.json();
                
                let newEntries = [];
                if (body.blocked_sites && Array.isArray(body.blocked_sites)) {
                    newEntries = body.blocked_sites;
                } else if (body.domains && Array.isArray(body.domains)) {
                    newEntries = body.domains.map(d => ({
                        domain: String(d).toLowerCase().trim().replace(/^www\./, ''),
                        url: '',
                        title: '',
                        description: '',
                        timestamp: Date.now()
                    }));
                } else {
                    return jsonResponse({ ok: false, error: 'No domains provided' }, 400, corsHeaders);
                }

                if (newEntries.length === 0) {
                    return jsonResponse({ ok: false, error: 'Empty list' }, 400, corsHeaders);
                }

                newEntries.forEach(entry => {
                    if(entry && entry.domain) {
                        entry.domain = String(entry.domain).toLowerCase().trim().replace(/^www\./, '');
                    }
                });
                newEntries = newEntries.filter(e => e && e.domain && e.domain.length > 0);

                const pendingRaw = await kvGet('pending_domains', '[]');
                const pending = JSON.parse(pendingRaw);

                let merged = [...pending];
                for (const entry of newEntries) {
                    const exists = merged.find(item => {
                        if (typeof item === 'string') return item === entry.domain;
                        return item && item.domain === entry.domain;
                    });
                    if (!exists) {
                        merged.push(entry);
                    }
                }

                await kvPut('pending_domains', JSON.stringify(merged));
                return jsonResponse({ ok: true, added: newEntries.length, total_pending: merged.length }, 200, corsHeaders);
            } catch (e) {
                return jsonResponse({ ok: false, error: e.message }, 500, corsHeaders);
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 1.5) POST /api/unblock
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/unblock' && method === 'POST') {
            try {
                const body = await request.json();
                if (!body.domain) return jsonResponse({ ok: false }, 400, corsHeaders);
                const domain = String(body.domain).toLowerCase().trim().replace(/^www\./, '');
                
                const requestsRaw = await kvGet('unblock_requests', '[]');
                const requests = JSON.parse(requestsRaw);
                const merged = [...new Set([...requests, domain])];
                await kvPut('unblock_requests', JSON.stringify(merged));
                return jsonResponse({ ok: true }, 200, corsHeaders);
            } catch (e) {
                return jsonResponse({ ok: false, error: e.message }, 500, corsHeaders);
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 2) GET /api/config
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/config' && method === 'GET') {
            const configRaw = await kvGet('config');
            let config = {
                version: '1.0.0',
                updated: new Date().toISOString(),
                blocked_domains: [],
                blocked_keywords: [],
                video_blocking: true,
                audio_blocking: true,
                shortcuts: []
            };
            if (configRaw) {
                try {
                    const parsed = JSON.parse(configRaw);
                    config = { ...config, ...parsed };
                } catch(e) {}
            }
            return new Response(JSON.stringify(config), { headers: { ...corsHeaders, 'Content-Type': 'application/json' } });
        }

        // ═══════════════════════════════════════════════════════════
        // 3) GET /admin
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/admin' && method === 'GET') {
            try {
                const password = url.searchParams.get('pw');
                const cookiePassword = getCookie(request, 'admin_pw');
                const adminPassword = env.ADMIN_PASSWORD || '';

                if (!adminPassword) {
                    return new Response(
                        '<h1 style="font-family:sans-serif;padding:40px;">⚠️ ADMIN_PASSWORD not set</h1>' +
                        '<p style="font-family:sans-serif;padding:0 40px;">Go to Worker Settings → Variables and secrets → Add ADMIN_PASSWORD</p>',
                        { status: 500, headers: { 'Content-Type': 'text/html' } }
                    );
                }

                if (password !== adminPassword && cookiePassword !== adminPassword) {
                    return new Response(loginPage(), { headers: { 'Content-Type': 'text/html' } });
                }

                const pendingRaw = await kvGet('pending_domains', '[]');
                const configRaw = await kvGet('config');
                const ignoredRaw = await kvGet('ignored_domains', '[]');
                const unblockReqsRaw = await kvGet('unblock_requests', '[]');
                const unblockReqs = JSON.parse(unblockReqsRaw);
                const pending = JSON.parse(pendingRaw);
                const ignored = JSON.parse(ignoredRaw);
                const config = configRaw ? JSON.parse(configRaw) : {
                    version: '1.0.0',
                    updated: new Date().toISOString(),
                    blocked_domains: [],
                    blocked_keywords: [],
                    video_blocking: true,
                    audio_blocking: true
                };

                const html = adminDashboard(pending, ignored, config, adminPassword, unblockReqs);
                return new Response(html, {
                    headers: {
                        'Content-Type': 'text/html',
                        'Set-Cookie': `admin_pw=${adminPassword}; Path=/; HttpOnly; SameSite=Strict; Max-Age=86400`
                    }
                });
            } catch (e) {
                return new Response(`<h1>Dashboard Error</h1><pre>${escapeHtml(e.message)}\n${escapeHtml(e.stack)}</pre>`, {
                    status: 500,
                    headers: { 'Content-Type': 'text/html' }
                });
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 4) POST /admin/action — WRAPPED IN FULL TRY-CATCH
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/admin/action' && method === 'POST') {
            try {
                // Auth check
                const cookiePassword = getCookie(request, 'admin_pw');
                const adminPassword = env.ADMIN_PASSWORD || '';
                if (!adminPassword || cookiePassword !== adminPassword) {
                    return new Response('Unauthorized', { status: 401 });
                }

                // Parse form data safely
                let formData;
                try {
                    formData = await request.formData();
                } catch (e) {
                    return new Response(`Form parse error: ${e.message}`, { status: 400 });
                }

                const action = formData.get('action');
                if (!action) {
                    return redirectToAdmin(url.origin);
                }
                const actionStr = String(action);

                // ── add_domains ──
                if (actionStr === 'add_domains') {
                    const domainsToAdd = formData.getAll('domains').map(d => String(d));
                    if (domainsToAdd.length === 0) {
                        return redirectToAdmin(url.origin);
                    }
                    const configRaw = await kvGet('config');
                    const config = configRaw ? JSON.parse(configRaw) : {
                        version: '1.0.0', updated: new Date().toISOString(),
                        blocked_domains: [], blocked_keywords: [], video_blocking: true, audio_blocking: true
                    };
                    const normalized = domainsToAdd
                        .map(d => d.toLowerCase().trim().replace(/^www\./, ''))
                        .filter(d => d.length > 0);
                    config.blocked_domains = [...new Set([...(config.blocked_domains || []), ...normalized])];
                    config.updated = new Date().toISOString();
                    config.version = bumpVersion(config.version);
                    await kvPut('config', JSON.stringify(config));

                    // Remove from pending
                    const pendingRaw = await kvGet('pending_domains', '[]');
                    const pending = JSON.parse(pendingRaw);
                    const newPending = pending.filter(d => !normalized.includes(typeof d === 'string' ? d.toLowerCase() : (d && d.domain ? d.domain.toLowerCase() : '')));
                    await kvPut('pending_domains', JSON.stringify(newPending));

                    // Also remove from ignored (in case it was ignored before)
                    const ignoredRaw = await kvGet('ignored_domains', '[]');
                    const ignored = JSON.parse(ignoredRaw);
                    const newIgnored = ignored.filter(d => !normalized.includes(d));
                    await kvPut('ignored_domains', JSON.stringify(newIgnored));

                    return redirectToAdmin(url.origin, 'added', domainsToAdd.length);
                }

                // ── ignore_domains ──
                if (actionStr === 'ignore_domains') {
                    const domainsToIgnore = formData.getAll('domains').map(d => String(d).toLowerCase().trim());

                    // Remove from pending
                    const pendingRaw = await kvGet('pending_domains', '[]');
                    const pending = JSON.parse(pendingRaw);
                    const newPending = pending.filter(d => !domainsToIgnore.includes(typeof d === 'string' ? d.toLowerCase() : (d && d.domain ? d.domain.toLowerCase() : '')));
                    await kvPut('pending_domains', JSON.stringify(newPending));

                    // Add to ignored list (persist so they don't come back)
                    const ignoredRaw = await kvGet('ignored_domains', '[]');
                    const ignored = JSON.parse(ignoredRaw);
                    const newIgnored = [...new Set([...ignored, ...domainsToIgnore])];
                    await kvPut('ignored_domains', JSON.stringify(newIgnored));

                    return redirectToAdmin(url.origin, 'ignored', domainsToIgnore.length);
                }

                // ── unignore_domain ──
                if (actionStr === 'unignore_domain') {
                    const domain = String(formData.get('domain') || '').toLowerCase().trim();
                    const ignoredRaw = await kvGet('ignored_domains', '[]');
                    const ignored = JSON.parse(ignoredRaw);
                    const newIgnored = ignored.filter(d => d !== domain);
                    await kvPut('ignored_domains', JSON.stringify(newIgnored));
                    return redirectToAdmin(url.origin, 'unignored', 1);
                }

                // ── remove_domain ──
                if (actionStr === 'remove_domain') {
                    const domain = String(formData.get('domain') || '');
                    const configRaw = await kvGet('config');
                    if (configRaw) {
                        const config = JSON.parse(configRaw);
                        config.blocked_domains = (config.blocked_domains || []).filter(d => d !== domain);
                        config.updated = new Date().toISOString();
                        config.version = bumpVersion(config.version);
                        await kvPut('config', JSON.stringify(config));
                    }
                    return redirectToAdmin(url.origin, 'removed', 1);
                }

                // ── add_shortcut ──
                if (actionStr === 'add_shortcut') {
                    const title = formData.get('title');
                    const urlVal = formData.get('url');
                    const icon = formData.get('icon') || '';
                    if (!title || !urlVal) return redirectToAdmin(url.origin);
                    const configRaw = await kvGet('config');
                    const config = configRaw ? JSON.parse(configRaw) : {
                        version: '1.0.0', updated: new Date().toISOString(),
                        blocked_domains: [], blocked_keywords: [], video_blocking: true, audio_blocking: true, shortcuts: []
                    };
                    config.shortcuts = config.shortcuts || [];
                    config.shortcuts.push({ title: String(title), url: String(urlVal), icon: String(icon) });
                    config.updated = new Date().toISOString();
                    config.version = bumpVersion(config.version);
                    await kvPut('config', JSON.stringify(config));
                    return redirectToAdmin(url.origin, 'added', 1);
                }

                // ── remove_shortcut ──
                if (actionStr === 'remove_shortcut') {
                    const urlVal = formData.get('url');
                    const configRaw = await kvGet('config');
                    if (configRaw) {
                        const config = JSON.parse(configRaw);
                        config.shortcuts = (config.shortcuts || []).filter(s => s.url !== urlVal);
                        config.updated = new Date().toISOString();
                        config.version = bumpVersion(config.version);
                        await kvPut('config', JSON.stringify(config));
                    }
                    return redirectToAdmin(url.origin, 'removed', 1);
                }

                // ── approve_unblock ──
                if (actionStr === 'approve_unblock') {
                    const domain = String(formData.get('domain') || '').toLowerCase().trim();
                    const reqsRaw = await kvGet('unblock_requests', '[]');
                    let reqs = JSON.parse(reqsRaw);
                    reqs = reqs.filter(d => d !== domain);
                    await kvPut('unblock_requests', JSON.stringify(reqs));
                    
                    const configRaw = await kvGet('config');
                    if (configRaw) {
                        const config = JSON.parse(configRaw);
                        config.blocked_domains = (config.blocked_domains || []).filter(d => d !== domain);
                        config.updated = new Date().toISOString();
                        config.version = bumpVersion(config.version);
                        await kvPut('config', JSON.stringify(config));
                    }
                    return redirectToAdmin(url.origin, 'removed', 1);
                }

                // ── deny_unblock ──
                if (actionStr === 'deny_unblock') {
                    const domain = String(formData.get('domain') || '').toLowerCase().trim();
                    const reqsRaw = await kvGet('unblock_requests', '[]');
                    let reqs = JSON.parse(reqsRaw);
                    reqs = reqs.filter(d => d !== domain);
                    await kvPut('unblock_requests', JSON.stringify(reqs));
                    return redirectToAdmin(url.origin, 'removed', 1);
                }

                // ── update_keywords ──
                if (actionStr === 'update_keywords') {
                    const keywords = formData.get('keywords');
                    const videoBlocking = formData.get('video_blocking') === 'on';
                    const audioBlocking = formData.get('audio_blocking') === 'on';
                    const configRaw = await kvGet('config');
                    const config = configRaw ? JSON.parse(configRaw) : {
                        version: '1.0.0', updated: new Date().toISOString(),
                        blocked_domains: [], blocked_keywords: [], video_blocking: true, audio_blocking: true
                    };
                    config.blocked_keywords = keywords
                        ? String(keywords).split(',').map(k => k.trim()).filter(k => k.length > 0)
                        : [];
                    config.video_blocking = videoBlocking;
                    config.audio_blocking = audioBlocking;
                    config.updated = new Date().toISOString();
                    config.version = bumpVersion(config.version);
                    await kvPut('config', JSON.stringify(config));
                    return redirectToAdmin(url.origin, 'updated');
                }

                // ── add_manual_domain ──
                if (actionStr === 'add_manual_domain') {
                    const domain = formData.get('domain');
                    if (!domain || String(domain).trim().length === 0) {
                        return redirectToAdmin(url.origin);
                    }
                    const normalized = String(domain).toLowerCase().trim().replace(/^www\./, '');
                    const configRaw = await kvGet('config');
                    const config = configRaw ? JSON.parse(configRaw) : {
                        version: '1.0.0', updated: new Date().toISOString(),
                        blocked_domains: [], blocked_keywords: [], video_blocking: true, audio_blocking: true
                    };
                    config.blocked_domains = [...new Set([...(config.blocked_domains || []), normalized])];
                    config.updated = new Date().toISOString();
                    config.version = bumpVersion(config.version);
                    await kvPut('config', JSON.stringify(config));
                    return redirectToAdmin(url.origin, 'added', 1);
                }

                return redirectToAdmin(url.origin);
            } catch (e) {
                // Return the actual error so we can debug
                return new Response(
                    `<h1>Action Error</h1><p><strong>${escapeHtml(e.message)}</strong></p><pre>${escapeHtml(e.stack || '')}</pre>`,
                    { status: 500, headers: { 'Content-Type': 'text/html' } }
                );
            }
        }

        // ═══════════════════════════════════════════════════════════
        // 5) ROOT → /admin
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/' || url.pathname === '') {
            return Response.redirect(url.origin + '/admin', 302);
        }

        // ═══════════════════════════════════════════════════════════
        // 6) /debug
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/debug') {
            const hasKv = !!env.SAFEBROWSER_KV;
            const hasPassword = !!env.ADMIN_PASSWORD;
            let kvTest = 'not tested';
            if (hasKv) {
                try {
                    await env.SAFEBROWSER_KV.put('_test_', 'ok');
                    const testVal = await env.SAFEBROWSER_KV.get('_test_');
                    kvTest = testVal === 'ok' ? 'working' : 'read mismatch';
                    await env.SAFEBROWSER_KV.delete('_test_');
                } catch (e) {
                    kvTest = 'error: ' + e.message;
                }
            }
            return jsonResponse({
                status: 'ok',
                kv_bound: hasKv,
                kv_test: kvTest,
                password_set: hasPassword,
                timestamp: new Date().toISOString()
            }, 200, corsHeaders);
        }

        // ═══════════════════════════════════════════════════════════
        // 7) /health
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/health') {
            return jsonResponse({ status: 'ok', timestamp: new Date().toISOString() }, 200, corsHeaders);
        }

        return new Response('Not Found', { status: 404 });
    }
};

// ── Helpers ──────────────────────────────────────────────────

function jsonResponse(data, status = 200, extraHeaders = {}) {
    return new Response(JSON.stringify(data), {
        status,
        headers: { 'Content-Type': 'application/json', ...extraHeaders }
    });
}

function redirectToAdmin(origin, action = '', count = 0) {
    const params = action ? `?msg=${action}&count=${count}` : '';
    return Response.redirect(origin + '/admin' + params, 302);
}

function getCookie(request, name) {
    const cookieHeader = request.headers.get('Cookie');
    if (!cookieHeader) return null;
    const match = cookieHeader.match(new RegExp(`(?:^|;\s*)${name}=([^;]*)`));
    return match ? decodeURIComponent(match[1]) : null;
}

function bumpVersion(version) {
    const parts = (version || '1.0.0').split('.');
    parts[2] = (parseInt(parts[2] || '0') + 1).toString();
    return parts.join('.');
}

function escapeHtml(text) {
    if (text == null) return '';
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// ── Login Page ──────────────────────────────────────────────

function loginPage() {
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SafeBrowser Admin - Login</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
    }
    .login-card {
      background: white;
      padding: 40px;
      border-radius: 16px;
      box-shadow: 0 20px 60px rgba(0,0,0,0.3);
      width: 100%;
      max-width: 400px;
      text-align: center;
    }
    .login-card h1 { color: #333; margin-bottom: 8px; font-size: 24px; }
    .login-card p { color: #888; margin-bottom: 30px; font-size: 14px; }
    .lock-icon { font-size: 48px; margin-bottom: 20px; }
    input[type="password"] {
      width: 100%;
      padding: 14px 18px;
      border: 2px solid #e0e0e0;
      border-radius: 10px;
      font-size: 16px;
      margin-bottom: 20px;
    }
    input[type="password"]:focus { outline: none; border-color: #667eea; }
    button {
      width: 100%;
      padding: 14px;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: white;
      border: none;
      border-radius: 10px;
      font-size: 16px;
      font-weight: 600;
      cursor: pointer;
    }
    button:hover { transform: translateY(-1px); box-shadow: 0 8px 20px rgba(102,126,234,0.4); }
  </style>
</head>
<body>
  <div class="login-card">
    <div class="lock-icon">🔒</div>
    <h1>SafeBrowser Admin</h1>
    <p>Enter your admin password to continue</p>
    <form method="GET" action="/admin">
      <input type="password" name="pw" placeholder="Admin password" required autofocus>
      <button type="submit">Enter Dashboard</button>
    </form>
  </div>
</body>
</html>`;
}

// ── Admin Dashboard ─────────────────────────────────────────

function adminDashboard(pending, ignored, config, adminPassword, unblockReqs = []) {
    const blockedDomains = config.blocked_domains || [];
    const blockedKeywords = config.blocked_keywords || [];
    const videoBlocking = config.video_blocking !== false;
    const audioBlocking = config.audio_blocking !== false;
    const version = config.version || '1.0.0';
    const updated = config.updated || 'Never';

    // Filter out domains already in blocklist or ignored
    const blockedSet = new Set((config.blocked_domains || []).map(d => d.toLowerCase()));
    const ignoredSet = new Set(ignored.map(d => d.toLowerCase()));
    const filteredPending = pending.filter(d => {
        const lower = (typeof d === 'string' ? d : (d && d.domain ? d.domain : '')).toLowerCase();
        return lower && !blockedSet.has(lower) && !ignoredSet.has(lower);
    });

    const pendingList = filteredPending.map((item) => {
        const dom = typeof item === 'string' ? item : (item.domain || '');
        const title = typeof item === 'string' ? '' : (item.title || '');
        const desc = typeof item === 'string' ? '' : (item.description || '');
        const titleHtml = title ? `<div style="font-size: 12px; color: #6b7280; margin-top: 4px; font-weight: 500;">${escapeHtml(title)}</div>` : '';
        const descHtml = desc ? `<div style="font-size: 11px; color: #9ca3af; margin-top: 2px; font-style: italic;">${escapeHtml(desc)}</div>` : '';
        return `
    <div class="domain-row">
      <label class="checkbox-label" style="align-items: flex-start;">
        <input type="checkbox" name="domains" value="${escapeHtml(dom)}" checked style="margin-top: 4px;">
        <div style="display: flex; flex-direction: column;">
          <span class="domain-name">${escapeHtml(dom)}</span>
          ${titleHtml}
          ${descHtml}
        </div>
      </label>
      <span class="device-badge" title="Tracked from Android">📱</span>
    </div>`;
    }).join('');

    const blockedList = blockedDomains.map(domain => `
    <div class="blocked-row">
      <span class="blocked-name">${escapeHtml(domain)}</span>
      <form method="POST" action="/admin/action" class="inline-form">
        <input type="hidden" name="action" value="remove_domain">
        <input type="hidden" name="domain" value="${escapeHtml(domain)}">
        <button type="submit" class="btn-remove" title="Remove">✕</button>
      </form>
    </div>
  `).join('');

    // pending inputs are inlined in the template below

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SafeBrowser Admin Dashboard</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
      background: #f5f7fa;
      color: #333;
      line-height: 1.6;
    }
    .header {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: white;
      padding: 30px 0;
      box-shadow: 0 4px 20px rgba(0,0,0,0.1);
    }
    .header-inner {
      max-width: 1200px;
      margin: 0 auto;
      padding: 0 20px;
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .header h1 { font-size: 28px; font-weight: 700; }
    .header-meta { text-align: right; font-size: 13px; opacity: 0.9; }
    .container { max-width: 1200px; margin: 0 auto; padding: 30px 20px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; }
    @media (max-width: 900px) {
      .grid { grid-template-columns: 1fr; }
      .stats-grid { grid-template-columns: repeat(2, 1fr) !important; }
      .header-inner { flex-direction: column; gap: 10px; text-align: center; }
      .header-meta { text-align: center; }
    }
    .card {
      background: white;
      border-radius: 16px;
      padding: 24px;
      box-shadow: 0 2px 12px rgba(0,0,0,0.06);
      border: 1px solid #e8ecf1;
    }
    .card h2 { font-size: 18px; margin-bottom: 16px; display: flex; align-items: center; gap: 10px; }
    .badge {
      background: #667eea;
      color: white;
      font-size: 12px;
      padding: 2px 10px;
      border-radius: 20px;
      font-weight: 600;
    }
    .badge.green { background: #10b981; }
    .badge.red { background: #ef4444; }
    .badge.gray { background: #9ca3af; }
    .domain-list {
      max-height: 400px;
      overflow-y: auto;
      border: 1px solid #e8ecf1;
      border-radius: 10px;
      margin-bottom: 16px;
    }
    .domain-row, .blocked-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 12px 16px;
      border-bottom: 1px solid #f0f0f0;
      transition: background 0.15s;
    }
    .domain-row:hover, .blocked-row:hover { background: #f8fafc; }
    .domain-row:last-child, .blocked-row:last-child { border-bottom: none; }
    .checkbox-label { display: flex; align-items: center; gap: 10px; cursor: pointer; flex: 1; }
    .checkbox-label input[type="checkbox"] { width: 18px; height: 18px; accent-color: #667eea; cursor: pointer; }
    .domain-name { font-size: 14px; color: #374151; font-weight: 500; }
    .device-badge { font-size: 14px; opacity: 0.6; }
    .blocked-name { font-size: 14px; color: #374151; font-weight: 500; flex: 1; }
    .btn {
      padding: 10px 20px;
      border: none;
      border-radius: 8px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      transition: all 0.2s;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .btn-primary { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; }
    .btn-primary:hover { transform: translateY(-1px); box-shadow: 0 6px 16px rgba(102,126,234,0.35); }
    .btn-success { background: #10b981; color: white; }
    .btn-success:hover { background: #059669; }
    .btn-danger { background: #ef4444; color: white; }
    .btn-danger:hover { background: #dc2626; }
    .btn-secondary { background: #f3f4f6; color: #6b7280; }
    .btn-secondary:hover { background: #e5e7eb; }
    .btn-remove { background: none; border: none; color: #ef4444; font-size: 16px; cursor: pointer; padding: 4px 8px; border-radius: 4px; }
    .btn-remove:hover { background: #fef2f2; }
    .inline-form { display: inline; }
    .empty-state { text-align: center; padding: 40px 20px; color: #9ca3af; font-size: 14px; }
    .empty-state .icon { font-size: 40px; margin-bottom: 12px; display: block; }
    .action-bar { display: flex; gap: 10px; flex-wrap: wrap; }
    .input-group { display: flex; gap: 10px; margin-bottom: 16px; }
    .input-group input {
      flex: 1;
      padding: 10px 14px;
      border: 2px solid #e5e7eb;
      border-radius: 8px;
      font-size: 14px;
    }
    .input-group input:focus { outline: none; border-color: #667eea; }
    .stats-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 24px; }
    .stat-card { background: white; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 2px 8px rgba(0,0,0,0.04); border: 1px solid #e8ecf1; }
    .stat-number { font-size: 32px; font-weight: 700; color: #667eea; line-height: 1; }
    .stat-label { font-size: 12px; color: #9ca3af; margin-top: 6px; text-transform: uppercase; letter-spacing: 0.5px; }
    .settings-row { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid #f0f0f0; }
    .settings-row:last-child { border-bottom: none; }
    .toggle-switch {
      position: relative;
      width: 48px;
      height: 26px;
      background: #e5e7eb;
      border-radius: 13px;
      cursor: pointer;
      transition: background 0.2s;
      appearance: none;
      -webkit-appearance: none;
    }
    .toggle-switch:checked { background: #667eea; }
    .toggle-switch::after {
      content: '';
      position: absolute;
      width: 22px;
      height: 22px;
      background: white;
      border-radius: 50%;
      top: 2px;
      left: 2px;
      transition: transform 0.2s;
      box-shadow: 0 1px 3px rgba(0,0,0,0.2);
    }
    .toggle-switch:checked::after { transform: translateX(22px); }
    .textarea {
      width: 100%;
      padding: 12px;
      border: 2px solid #e5e7eb;
      border-radius: 8px;
      font-size: 14px;
      font-family: inherit;
      resize: vertical;
      min-height: 80px;
    }
    .textarea:focus { outline: none; border-color: #667eea; }
    .toast {
      position: fixed;
      top: 20px;
      right: 20px;
      background: #10b981;
      color: white;
      padding: 14px 24px;
      border-radius: 10px;
      box-shadow: 0 10px 30px rgba(0,0,0,0.15);
      font-weight: 600;
      animation: slideIn 0.3s ease;
      z-index: 1000;
    }
    @keyframes slideIn {
      from { transform: translateX(100px); opacity: 0; }
      to { transform: translateX(0); opacity: 1; }
    }
    .footer { text-align: center; padding: 30px; color: #9ca3af; font-size: 13px; }
  </style>
</head>
<body>
  <div class="header">
    <div class="header-inner">
      <div><h1>🛡️ SafeBrowser Admin</h1></div>
      <div class="header-meta">
        <div>Version: <strong>${escapeHtml(version)}</strong></div>
        <div>Updated: <strong>${escapeHtml(updated)}</strong></div>
      </div>
    </div>
  </div>

  <div class="container">
    <div class="stats-grid" style="grid-template-columns: repeat(4, 1fr);">
      <div class="stat-card"><div class="stat-number">${filteredPending.length}</div><div class="stat-label">Pending</div></div>
      <div class="stat-card"><div class="stat-number">${blockedDomains.length}</div><div class="stat-label">Blocked</div></div>
      <div class="stat-card"><div class="stat-number">${ignored.length}</div><div class="stat-label">Ignored</div></div>
      <div class="stat-card"><div class="stat-number">${blockedKeywords.length}</div><div class="stat-label">Keywords</div></div>
    </div>

    <div class="grid">
      <!-- Pending Domains -->
      <div class="card">
        <h2>📥 Pending Domains <span class="badge ${filteredPending.length > 0 ? 'red' : 'gray'}">${filteredPending.length}</span></h2>
        ${filteredPending.length === 0 ? `
          <div class="empty-state"><span class="icon">📭</span>No pending domains to review</div>
        ` : `
          <form method="POST" action="/admin/action">
            <input type="hidden" name="action" value="add_domains">
            <div class="domain-list">${pendingList}</div>
            <div class="action-bar"><button type="submit" class="btn btn-success">✓ Add Selected to Blocklist</button></div>
          </form>
          <form method="POST" action="/admin/action" style="margin-top:10px">
            <input type="hidden" name="action" value="ignore_domains">
            ${filteredPending.map((d) => `<input type="hidden" name="domains" value="${escapeHtml(d)}">`).join('')}
            <button type="submit" class="btn btn-secondary">🗑 Ignore All</button>
          </form>
        `}
      </div>

      <!-- Blocked Domains -->
      <div class="card">
        <h2>🚫 Blocked Domains <span class="badge">${blockedDomains.length}</span></h2>
        <form method="POST" action="/admin/action" class="input-group">
          <input type="hidden" name="action" value="add_manual_domain">
          <input type="text" name="domain" placeholder="Add domain manually (e.g. example.com)" required>
          <button type="submit" class="btn btn-primary">+ Add</button>
        </form>
        <div class="domain-list">
          ${blockedDomains.length === 0 ? `<div class="empty-state"><span class="icon">🌐</span>No domains blocked yet</div>` : blockedList}
        </div>
      </div>

      <!-- Ignored Domains -->
      <div class="card">
        <h2>
          🚫 Ignored Domains
          <span class="badge gray">${ignored.length}</span>
        </h2>
        <div class="domain-list">
          ${ignored.length === 0 ? `
            <div class="empty-state">
              <span class="icon">🤷</span>
              No ignored domains
            </div>
          ` : ignored.map(domain => `
            <div class="blocked-row">
              <span class="blocked-name">${escapeHtml(domain)}</span>
              <form method="POST" action="/admin/action" class="inline-form">
                <input type="hidden" name="action" value="unignore_domain">
                <input type="hidden" name="domain" value="${escapeHtml(domain)}">
                <button type="submit" class="btn-remove" title="Un-ignore">↩</button>
              </form>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- Settings -->
      <div class="card">
        <h2>⚙️ Blocklist Settings</h2>
        <form method="POST" action="/admin/action">
          <input type="hidden" name="action" value="update_keywords">
          <div style="margin-bottom: 16px">
            <label style="font-size: 13px; color: #6b7280; font-weight: 600; display: block; margin-bottom: 8px;">BLOCKED KEYWORDS (comma-separated)</label>
            <textarea name="keywords" class="textarea" placeholder="e.g. porn, gambling, torrent">${escapeHtml(blockedKeywords.join(', '))}</textarea>
          </div>
          <div class="settings-row">
            <div>
              <div style="font-weight: 600; color: #374151;">Video Blocking</div>
              <div style="font-size: 12px; color: #9ca3af;">Block all HTML5 video playback</div>
            </div>
            <input type="checkbox" name="video_blocking" class="toggle-switch" ${videoBlocking ? 'checked' : ''}>
          </div>
          <div class="settings-row">
            <div>
              <div style="font-weight: 600; color: #374151;">Audio Blocking</div>
              <div style="font-size: 12px; color: #9ca3af;">Block all background audio and music</div>
            </div>
            <input type="checkbox" name="audio_blocking" class="toggle-switch" ${audioBlocking ? 'checked' : ''}>
          </div>
          <div style="margin-top: 16px"><button type="submit" class="btn btn-primary">💾 Save Settings</button></div>
        </form>
      </div>


      <!-- Unblock Requests -->
      <div class="card">
        <h2>🔓 Unblock Requests <span class="badge ${unblockReqs.length > 0 ? 'red' : 'gray'}">${unblockReqs.length}</span></h2>
        <div class="domain-list">
          ${unblockReqs.length === 0 ? `<div class="empty-state"><span class="icon">✅</span>No unblock requests</div>` : unblockReqs.map(domain => `
            <div class="blocked-row">
              <span class="blocked-name">${escapeHtml(domain)}</span>
              <div>
                  <form method="POST" action="/admin/action" class="inline-form">
                    <input type="hidden" name="action" value="approve_unblock">
                    <input type="hidden" name="domain" value="${escapeHtml(domain)}">
                    <button type="submit" class="btn-remove" style="color:#10b981;" title="Approve & Unblock">✓</button>
                  </form>
                  <form method="POST" action="/admin/action" class="inline-form">
                    <input type="hidden" name="action" value="deny_unblock">
                    <input type="hidden" name="domain" value="${escapeHtml(domain)}">
                    <button type="submit" class="btn-remove" title="Deny & Dismiss">✕</button>
                  </form>
              </div>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- Quick Shortcuts -->
      <div class="card">
        <h2>🔗 Quick Shortcuts <span class="badge">${(config.shortcuts || []).length}</span></h2>
        <form method="POST" action="/admin/action" class="input-group">
          <input type="hidden" name="action" value="add_shortcut">
          <input type="text" name="title" placeholder="Title (e.g. Google)" required style="width: 30%">
          <input type="url" name="url" placeholder="https://..." required style="width: 50%">
          <input type="text" name="icon" placeholder="Icon (e.g. 🔍)" style="width: 20%">
          <button type="submit" class="btn btn-primary">+ Add</button>
        </form>
        <div class="domain-list">
          ${(config.shortcuts || []).length === 0 ? `<div class="empty-state"><span class="icon">🔗</span>No shortcuts configured</div>` : (config.shortcuts || []).map(s => `
            <div class="blocked-row">
              <span class="blocked-name">${escapeHtml(s.icon)} ${escapeHtml(s.title)} <small style="opacity:0.5">(${escapeHtml(s.url)})</small></span>
              <form method="POST" action="/admin/action" class="inline-form">
                <input type="hidden" name="action" value="remove_shortcut">
                <input type="hidden" name="url" value="${escapeHtml(s.url)}">
                <button type="submit" class="btn-remove" title="Remove">✕</button>
              </form>
            </div>
          `).join('')}
        </div>
      </div>

      <!-- Quick Info -->
      <div class="card">
        <h2>📊 Quick Info</h2>
        <div style="font-size: 14px; color: #6b7280; line-height: 2;">
          <div><strong>API:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">POST /api/domains</code></div>
          <div><strong>Config:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">GET /api/config</code></div>
          <div><strong>Debug:</strong> <code style="background:#f3f4f6;padding:2px 6px;border-radius:4px;font-size:12px;">GET /debug</code></div>
          <div style="margin-top: 12px; padding-top: 12px; border-top: 1px solid #f0f0f0;">
            <strong>Android URLs:</strong>
            <div style="margin-top: 8px; background: #f8fafc; padding: 12px; border-radius: 8px; font-family: monospace; font-size: 12px; line-height: 1.8;">
              UPLOAD_URL = "https://browser.proxybotkk.workers.dev/api/domains"<br>
              REMOTE_CONFIG_URL = "https://browser.proxybotkk.workers.dev/api/config"
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="footer">SafeBrowser Admin Dashboard • Built with Cloudflare Workers + KV</div>

  <script>
    const params = new URLSearchParams(window.location.search);
    const msg = params.get('msg');
    const count = params.get('count');
    if (msg) {
      const messages = {
        added: count + ' domain(s) added to blocklist',
        ignored: count + ' domain(s) ignored',
        removed: 'Domain removed from blocklist',
        unignored: 'Domain un-ignored and will show in pending again',
        updated: 'Settings updated successfully'
      };
      const toast = document.createElement('div');
      toast.className = 'toast';
      toast.textContent = messages[msg] || 'Action completed';
      document.body.appendChild(toast);
      setTimeout(() => toast.remove(), 3000);
      window.history.replaceState({}, '', '/admin');
    }
  </script>
</body>
</html>`;
}
