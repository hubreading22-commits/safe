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
                
                if (ctx && ctx.waitUntil) {
                    ctx.waitUntil(classifyBackground(env, newEntries));
                }

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

                const classRaw = await kvGet('domain_classifications', '{}');
                const classifications = JSON.parse(classRaw);
                const policy = await getCategoryPolicy(env);

                const html = adminDashboard(pending, ignored, config, adminPassword, unblockReqs, classifications, policy);
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
                    config.manual_blocks = [...new Set([...(config.manual_blocks || []), ...normalized])];
                    await kvPut('config', JSON.stringify(config));
                    await rebuildEffectiveConfig(env);
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

                // ── clear_pending_domains ──
                if (actionStr === 'clear_pending_domains') {
                    const domainsToClear = formData.getAll('domains').map(d => String(d).toLowerCase().trim());

                    // Remove from pending without adding to any other list
                    const pendingRaw = await kvGet('pending_domains', '[]');
                    const pending = JSON.parse(pendingRaw);
                    const newPending = pending.filter(d => !domainsToClear.includes(typeof d === 'string' ? d.toLowerCase() : (d && d.domain ? d.domain.toLowerCase() : '')));
                    await kvPut('pending_domains', JSON.stringify(newPending));

                    return redirectToAdmin(url.origin, 'cleared', domainsToClear.length);
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
                        config.manual_blocks = (config.manual_blocks || []).filter(d => d !== domain);
                        config.manual_allows = [...new Set([...(config.manual_allows || []), domain])];
                        await kvPut('config', JSON.stringify(config));
                        await rebuildEffectiveConfig(env);
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
                        config.manual_blocks = (config.manual_blocks || []).filter(d => d !== domain);
                        config.manual_allows = [...new Set([...(config.manual_allows || []), domain])];
                        await kvPut('config', JSON.stringify(config));
                        await rebuildEffectiveConfig(env);
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
                    return redirectToAdmin(url.origin, 'req_denied', 1);
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
                    config.manual_blocks = [...new Set([...(config.manual_blocks || []), normalized])];
                    await kvPut('config', JSON.stringify(config));
                    await rebuildEffectiveConfig(env);
                    config.updated = new Date().toISOString();
                    config.version = bumpVersion(config.version);
                    await kvPut('config', JSON.stringify(config));
                    return redirectToAdmin(url.origin, 'added', 1);
                }

                // ── update_policy ──
                if (actionStr === 'update_policy') {
                    const category = String(formData.get('category'));
                    const rule = String(formData.get('rule')); // "BLOCK" or "ALLOW"
                    if (category && (rule === 'BLOCK' || rule === 'ALLOW')) {
                        const policy = await getCategoryPolicy(env);
                        policy[category] = rule;
                        await kvPut('category_policy', JSON.stringify(policy));
                        await rebuildEffectiveConfig(env);
                        return redirectToAdmin(url.origin, 'updated');
                    }
                    return redirectToAdmin(url.origin);
                }

                // ── remove_classification ──
                if (actionStr === 'remove_classification') {
                    const dom = String(formData.get('domain')).toLowerCase();
                    const classRaw = await kvGet('domain_classifications', '{}');
                    const classifications = JSON.parse(classRaw);
                    if (classifications[dom]) {
                        delete classifications[dom];
                        await kvPut('domain_classifications', JSON.stringify(classifications));
                        await rebuildEffectiveConfig(env);
                        return redirectToAdmin(url.origin, 'removed');
                    }
                    return redirectToAdmin(url.origin);
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
    },

    async scheduled(event, env, ctx) {
        await runScheduler(env, ctx);
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

function adminDashboard(pending, ignored, config, adminPassword, unblockReqs = [], classifications = {}, policy = {}) {
    const blockedDomains = config.blocked_domains || [];
    const manualBlocks = config.manual_blocks || [];
    const manualAllows = config.manual_allows || [];
    const blockedKeywords = config.blocked_keywords || [];
    const videoBlocking = config.video_blocking !== false;
    const audioBlocking = config.audio_blocking !== false;
    const version = config.version || '1.0.0';
    const updated = config.updated || 'Never';

    // Set of effectively blocked domains
    const effectiveBlockedSet = new Set(blockedDomains.map(d => d.toLowerCase()));
    
    // Manual sets
    const manualBlockSet = new Set(manualBlocks.map(d => d.toLowerCase()));
    const manualAllowSet = new Set(manualAllows.map(d => d.toLowerCase()));
    const ignoredSet = new Set(ignored.map(d => d.toLowerCase())); // legacy

    const TAXONOMY_CATEGORIES = [
        "EDUCATION", "GOVERNMENT", "NEWS", "TECHNOLOGY", "AI_TOOLS", "SEARCH_ENGINE",
        "REFERENCE", "HEALTH", "FINANCE", "SHOPPING", "PRODUCTIVITY", "COMMUNICATION",
        "SOCIAL_MEDIA", "FORUM_COMMUNITY", "VIDEO_STREAMING", "MUSIC_AUDIO",
        "ENTERTAINMENT", "GAMING", "DATING", "GAMBLING", "ADULT_SEXUAL", "DRUGS",
        "WEAPONS", "GRAPHIC_VIOLENCE", "HATE_EXTREMISM", "MALWARE_PHISHING",
        "SCAM_FRAUD", "PROXY_VPN_BYPASS", "FILE_SHARING", "CLOUD_STORAGE",
        "DOWNLOAD_SITE", "ADVERTISEMENT", "OTHER", "UNKNOWN"
    ];

    const filteredPending = pending.filter(d => {
        const lower = (typeof d === 'string' ? d : (d && d.domain ? d.domain : '')).toLowerCase();
        return lower && !classifications[lower] && !manualBlockSet.has(lower) && !manualAllowSet.has(lower) && !ignoredSet.has(lower);
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
        <input type="checkbox" name="domains" value="${escapeHtml(dom)}" style="margin-top: 4px;">
        <div style="display:flex; flex-direction:column; max-width: 80%;">
            <span class="domain-name" style="word-break: break-all;">${escapeHtml(dom)}</span>
            ${titleHtml}
            ${descHtml}
        </div>
      </label>
      <button type="button" class="btn-secondary btn-small" onclick="openDataModal('${escapeHtml(dom)}', '${escapeHtml(title).replace(/'/g, "\\'")}', '${escapeHtml(desc).replace(/'/g, "\\'")}')" style="align-self: flex-start; margin-top:2px;">Data</button>
    </div>`;
    }).join('');

    const manualBlockListHtml = manualBlocks.map(d => `
    <div class="domain-row">
      <span class="domain-name">${escapeHtml(d)}</span>
      <form method="POST" action="/admin/action" style="margin:0;">
        <input type="hidden" name="action" value="remove_domain">
        <input type="hidden" name="domain" value="${escapeHtml(d)}">
        <button type="submit" class="btn-danger btn-small">Remove</button>
      </form>
    </div>`).join('');

    const manualAllowListHtml = manualAllows.map(d => `
    <div class="domain-row">
      <span class="domain-name">${escapeHtml(d)}</span>
      <form method="POST" action="/admin/action" style="margin:0;">
        <input type="hidden" name="action" value="unignore_domain">
        <input type="hidden" name="domain" value="${escapeHtml(d)}">
        <button type="submit" class="btn-danger btn-small">Remove</button>
      </form>
    </div>`).join('');

    const policyCardsHtml = TAXONOMY_CATEGORIES.map(cat => {
        const currentRule = policy[cat] || (["ADULT_SEXUAL", "DRUGS", "WEAPONS", "GRAPHIC_VIOLENCE", "HATE_EXTREMISM", "MALWARE_PHISHING", "SCAM_FRAUD", "GAMBLING", "DATING"].includes(cat) ? "BLOCK" : "ALLOW");
        const isBlock = currentRule === "BLOCK";
        
        return `
        <div class="policy-card">
            <div class="policy-cat-name">${escapeHtml(cat.replace(/_/g, ' '))}</div>
            <div class="policy-toggle">
                <form method="POST" action="/admin/action" style="display:inline;">
                    <input type="hidden" name="action" value="update_policy">
                    <input type="hidden" name="category" value="${escapeHtml(cat)}">
                    <input type="hidden" name="rule" value="ALLOW">
                    <button type="submit" class="btn-toggle ${!isBlock ? 'active-allow' : ''}">ALLOW</button>
                </form>
                <form method="POST" action="/admin/action" style="display:inline;">
                    <input type="hidden" name="action" value="update_policy">
                    <input type="hidden" name="category" value="${escapeHtml(cat)}">
                    <input type="hidden" name="rule" value="BLOCK">
                    <button type="submit" class="btn-toggle ${isBlock ? 'active-block' : ''}">BLOCK</button>
                </form>
            </div>
        </div>`;
    }).join('');

    // Pre-build classifications grouped by category
    const classGrouped = {};
    for (const [dom, meta] of Object.entries(classifications)) {
        const cat = meta.category || "UNKNOWN";
        if (!classGrouped[cat]) classGrouped[cat] = [];
        classGrouped[cat].push({ domain: dom, ...meta });
    }

    // Classifications view
    const classCategories = Object.keys(classGrouped).sort();
    let classOptionsHtml = '<option value="ALL">-- Show All Categories --</option>';
    let classListHtml = '';

    classCategories.forEach(cat => {
        classOptionsHtml += `<option value="${escapeHtml(cat)}">${escapeHtml(cat)} (${classGrouped[cat].length})</option>`;
        
        classGrouped[cat].forEach(c => {
            const isBlocked = effectiveBlockedSet.has(c.domain.toLowerCase()) ? '<span class="badge badge-block">BLOCKED</span>' : '<span class="badge badge-allow">ALLOWED</span>';
            classListHtml += `
            <div class="domain-row class-row" data-category="${escapeHtml(cat)}">
                <div style="display:flex; flex-direction:column;">
                    <span class="domain-name" style="word-break: break-all;">${escapeHtml(c.domain)} ${isBlocked}</span>
                    <div style="font-size: 11px; color: #6b7280; margin-top: 2px;">
                        <strong>${escapeHtml(cat)}</strong> | Conf: ${(c.confidence*100).toFixed(0)}%
                        <br/><i style="color:#9ca3af">${escapeHtml(c.reason || '')}</i>
                    </div>
                </div>
                <form method="POST" action="/admin/action" style="margin:0;">
                    <input type="hidden" name="action" value="remove_classification">
                    <input type="hidden" name="domain" value="${escapeHtml(c.domain)}">
                    <button type="submit" class="btn-danger btn-small">Delete</button>
                </form>
            </div>`;
        });
    });

    const unblockReqsHtml = unblockReqs.map(req => `
    <div class="domain-row" style="flex-direction:column; align-items:flex-start;">
      <div style="display:flex; justify-content:space-between; width:100%; margin-bottom:8px;">
        <span class="domain-name">${escapeHtml(req.domain)}</span>
        <span style="font-size:12px; color:#888;">${new Date(req.time).toLocaleString()}</span>
      </div>
      <div style="font-size:13px; margin-bottom:10px; background:#f9fafb; padding:8px; border-radius:6px; border:1px solid #e5e7eb; width:100%;">
        <strong>Reason:</strong> ${escapeHtml(req.reason)}<br>
        <strong>Email:</strong> ${escapeHtml(req.email)}
      </div>
      <div style="display:flex; gap:10px;">
        <form method="POST" action="/admin/action" style="margin:0;">
          <input type="hidden" name="action" value="approve_unblock">
          <input type="hidden" name="domain" value="${escapeHtml(req.domain)}">
          <button type="submit" class="btn-primary btn-small">Approve & Allow</button>
        </form>
        <form method="POST" action="/admin/action" style="margin:0;">
          <input type="hidden" name="action" value="deny_unblock">
          <input type="hidden" name="domain" value="${escapeHtml(req.domain)}">
          <button type="submit" class="btn-danger btn-small">Deny</button>
        </form>
      </div>
    </div>`).join('');

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>SafeBrowser Dashboard</title>
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f3f4f6; color: #1f2937; padding: 20px; }
    .header { background: linear-gradient(135deg, #667eea 0%, #764ba2 100%); color: white; padding: 20px 30px; border-radius: 12px; margin-bottom: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); display: flex; justify-content: space-between; align-items: center; }
    .header h1 { font-size: 24px; font-weight: 700; }
    .stats { font-size: 14px; opacity: 0.9; margin-top: 5px; }
    
    .grid { display: grid; grid-template-columns: 1fr; gap: 20px; }
    @media(min-width: 1024px) { .grid { grid-template-columns: 1fr 1fr; } }
    @media(min-width: 1440px) { .grid { grid-template-columns: 1fr 1fr 1fr; } }
    
    .card { background: white; border-radius: 12px; box-shadow: 0 2px 4px rgba(0,0,0,0.05); overflow: hidden; display: flex; flex-direction: column;}
    .card-header { padding: 16px 20px; border-bottom: 1px solid #f3f4f6; background: #fafafa; display: flex; justify-content: space-between; align-items: center; }
    .card-header h2 { font-size: 16px; font-weight: 600; color: #374151; }
    .badge { background: #e5e7eb; padding: 4px 8px; border-radius: 20px; font-size: 12px; font-weight: 600; color: #4b5563; }
    .badge-block { background: #fee2e2; color: #991b1b; }
    .badge-allow { background: #d1fae5; color: #065f46; }
    
    .card-body { padding: 20px; max-height: 500px; overflow-y: auto; flex: 1;}
    .domain-row { display: flex; justify-content: space-between; align-items: center; padding: 12px 0; border-bottom: 1px solid #f3f4f6; }
    .domain-row:last-child { border-bottom: none; }
    .domain-name { font-weight: 500; font-size: 14px; color: #111827; }
    
    .checkbox-label { display: flex; align-items: center; gap: 10px; cursor: pointer; flex: 1; }
    
    button { padding: 8px 16px; border-radius: 6px; font-size: 13px; font-weight: 500; cursor: pointer; border: none; transition: all 0.2s; }
    .btn-primary { background: #667eea; color: white; }
    .btn-primary:hover { background: #5a67d8; }
    .btn-secondary { background: #e5e7eb; color: #374151; }
    .btn-secondary:hover { background: #d1d5db; }
    .btn-danger { background: #fee2e2; color: #dc2626; }
    .btn-danger:hover { background: #fecaca; }
    .btn-small { padding: 6px 12px; font-size: 12px; }
    
    input[type="text"] { width: 100%; padding: 10px 14px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; margin-bottom: 10px; }
    input[type="text"]:focus { outline: none; border-color: #667eea; ring: 2px solid #e0e7ff; }
    select { width: 100%; padding: 10px 14px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; margin-bottom: 10px; background: white;}
    
    .toast { position: fixed; bottom: 20px; right: 20px; background: #10b981; color: white; padding: 12px 24px; border-radius: 8px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); transform: translateY(100px); opacity: 0; transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1); }
    .toast.show { transform: translateY(0); opacity: 1; }

    /* Policy Toggles */
    .policy-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .policy-card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px; display: flex; justify-content: space-between; align-items: center;}
    .policy-cat-name { font-weight: 600; font-size: 12px; color: #374151; }
    .policy-toggle { display: flex; gap: 4px; background: #e5e7eb; padding: 3px; border-radius: 6px; }
    .btn-toggle { padding: 4px 10px; font-size: 11px; border-radius: 4px; background: transparent; color: #6b7280; font-weight: 600; }
    .btn-toggle.active-block { background: #ef4444; color: white; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }
    .btn-toggle.active-allow { background: #10b981; color: white; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }

    /* Modal */
    .modal-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; opacity: 0; pointer-events: none; transition: opacity 0.2s; z-index: 50;}
    .modal-overlay.active { opacity: 1; pointer-events: auto; }
    .modal-content { background: white; padding: 24px; border-radius: 12px; width: 90%; max-width: 500px; transform: scale(0.95); transition: transform 0.2s; }
    .modal-overlay.active .modal-content { transform: scale(1); }
    .modal-title { font-size: 18px; font-weight: 600; margin-bottom: 16px; border-bottom: 1px solid #e5e7eb; padding-bottom: 12px; }
  </style>
</head>
<body>

  <div class="header">
    <div>
      <h1>🛡️ SafeBrowser Admin</h1>
      <div class="stats">Effective Config v${escapeHtml(version)} • Last compiled: ${new Date(updated).toLocaleString()}</div>
    </div>
    <form method="POST" action="/admin/action" style="margin:0;">
      <input type="hidden" name="action" value="update_config">
      <button type="submit" class="btn-primary" style="background: rgba(255,255,255,0.2); border: 1px solid rgba(255,255,255,0.4);">Trigger Manual Rebuild</button>
    </form>
  </div>

  <div class="grid">
    
    <!-- AI Category Policies -->
    <div class="card" style="grid-column: 1 / -1;">
      <div class="card-header">
        <h2>🧠 AI Category Policies</h2>
        <span class="badge" style="background:#e0e7ff; color:#4f46e5;">Auto-blocks based on AI</span>
      </div>
      <div class="card-body">
        <p style="font-size:13px; color:#6b7280; margin-bottom:15px;">Configure the default action for each category. When the AI classifies an unknown domain, it checks these rules.</p>
        <div class="policy-grid">
            ${policyCardsHtml}
        </div>
      </div>
    </div>

    <!-- Domain Classifications -->
    <div class="card">
      <div class="card-header">
        <h2>🤖 AI Classifications</h2>
        <span class="badge">${Object.keys(classifications).length} domains</span>
      </div>
      <div class="card-body">
        <select id="classFilter" onchange="filterClassifications()">
            ${classOptionsHtml}
        </select>
        <div id="classList">
            ${classListHtml || '<div style="color:#888; font-size:14px; text-align:center; padding:20px;">No domains classified yet</div>'}
        </div>
      </div>
    </div>

    <!-- Pending Domains -->
    <div class="card">
      <div class="card-header">
        <h2>📥 Pending AI Analysis</h2>
        <span class="badge">${filteredPending.length} waiting</span>
      </div>
      <div class="card-body" style="padding:0;">
        <form method="POST" action="/admin/action" id="pendingForm" style="height:100%; display:flex; flex-direction:column;">
          <div style="padding:15px; border-bottom:1px solid #f3f4f6; background:#f9fafb; display:flex; gap:10px;">
            <button type="button" class="btn-secondary" onclick="selectAll()">Select All</button>
            <button type="submit" name="action" value="add_domains" class="btn-danger" style="flex:1;">Force Block</button>
            <button type="submit" name="action" value="ignore_domains" class="btn-primary" style="flex:1;">Force Allow</button>
            <button type="submit" name="action" value="clear_selected_pending" class="btn-secondary" style="flex:1;">Remove</button>
          </div>
          <div style="padding:20px; overflow-y:auto; flex:1;">
            ${pendingList || '<div style="color:#888; font-size:14px; text-align:center; padding:20px;">No pending domains</div>'}
          </div>
        </form>
      </div>
    </div>

    <!-- Manual Blocks -->
    <div class="card">
      <div class="card-header">
        <h2>⛔ Manual Blocks</h2>
        <span class="badge-block">${manualBlocks.length} forced blocks</span>
      </div>
      <div class="card-body">
        <form method="POST" action="/admin/action" style="margin-bottom: 20px; display:flex; gap:10px;">
          <input type="hidden" name="action" value="add_manual_domain">
          <input type="text" name="domain" placeholder="example.com" required style="margin:0;">
          <button type="submit" class="btn-primary">Add Block</button>
        </form>
        ${manualBlockListHtml || '<div style="color:#888; font-size:14px; text-align:center; padding:20px;">No manual blocks</div>'}
      </div>
    </div>
    
    <!-- Manual Allows -->
    <div class="card">
      <div class="card-header">
        <h2>✅ Manual Allows</h2>
        <span class="badge-allow">${manualAllows.length} forced allows</span>
      </div>
      <div class="card-body">
        <form method="POST" action="/admin/action" style="margin-bottom: 20px; display:flex; gap:10px;">
          <input type="hidden" name="action" value="add_manual_allow">
          <input type="text" name="domain" placeholder="example.com" required style="margin:0;">
          <button type="submit" class="btn-primary">Add Allow</button>
        </form>
        ${manualAllowListHtml || '<div style="color:#888; font-size:14px; text-align:center; padding:20px;">No manual allows</div>'}
      </div>
    </div>

    <!-- Unblock Requests -->
    <div class="card">
      <div class="card-header">
        <h2>🔓 Unblock Requests</h2>
        <span class="badge">${unblockReqs.length} pending</span>
      </div>
      <div class="card-body">
        ${unblockReqsHtml || '<div style="color:#888; font-size:14px; text-align:center; padding:20px;">No unblock requests</div>'}
      </div>
    </div>

  </div>

  <div id="toast" class="toast">Action completed successfully</div>

  <!-- Modal -->
  <div class="modal-overlay" id="dataModal">
    <div class="modal-content">
      <div class="modal-title" id="modalDom">Domain Name</div>
      <div style="font-size:13px; margin-bottom:10px;"><strong>Title:</strong> <span id="modalTitle" style="color:#4b5563;"></span></div>
      <div style="font-size:13px; margin-bottom:20px;"><strong>Description:</strong> <span id="modalDesc" style="color:#4b5563;"></span></div>
      <button class="btn-secondary" style="width:100%;" onclick="closeDataModal()">Close</button>
    </div>
  </div>

  <script>
    const params = new URLSearchParams(window.location.search);
    const msg = params.get('msg');
    if (msg) {
      const toast = document.getElementById('toast');
      const count = params.get('count');
      if (msg === 'added') toast.textContent = 'Added ' + count + ' domains to list';
      else if (msg === 'updated') toast.textContent = 'Policy updated successfully';
      else if (msg === 'removed') toast.textContent = 'Removed successfully';
      else if (msg === 'approved') toast.textContent = 'Unblock request approved';
      else if (msg === 'denied') toast.textContent = 'Unblock request denied';
      
      toast.classList.add('show');
      setTimeout(() => toast.classList.remove('show'), 3000);
      window.history.replaceState({}, document.title, '/admin');
    }

    function filterClassifications() {
        const cat = document.getElementById('classFilter').value;
        const rows = document.querySelectorAll('.class-row');
        rows.forEach(row => {
            if (cat === 'ALL' || row.dataset.category === cat) {
                row.style.display = 'flex';
            } else {
                row.style.display = 'none';
            }
        });
    }

    function openDataModal(dom, title, desc) {
      document.getElementById('modalDom').textContent = dom || 'N/A';
      document.getElementById('modalTitle').textContent = title || 'No Title Available';
      document.getElementById('modalDesc').textContent = desc || 'No Description Available';
      document.getElementById('dataModal').classList.add('active');
    }
    function closeDataModal() {
      document.getElementById('dataModal').classList.remove('active');
    }
    
    let lastChecked = null;
    const checkboxes = document.querySelectorAll('input[name="domains"]');
    checkboxes.forEach(chk => {
        chk.addEventListener('click', function(e) {
            if (!lastChecked) {
                lastChecked = this;
                return;
            }
            if (e.shiftKey) {
                const start = Array.from(checkboxes).indexOf(this);
                const end = Array.from(checkboxes).indexOf(lastChecked);
                const min = Math.min(start, end);
                const max = Math.max(start, end);
                for (let i = min; i <= max; i++) {
                    checkboxes[i].checked = lastChecked.checked;
                }
            }
            lastChecked = this;
        });
    });

    let allSelected = false;
    function selectAll() {
        allSelected = !allSelected;
        checkboxes.forEach(cb => cb.checked = allSelected);
    }
  </script>
</body>
</html>`;
}


// ── AI Classification & Policy Engine ──────────────────────────────────────

const TAXONOMY = [
    "EDUCATION", "GOVERNMENT", "NEWS", "TECHNOLOGY", "AI_TOOLS", "SEARCH_ENGINE",
    "REFERENCE", "HEALTH", "FINANCE", "SHOPPING", "PRODUCTIVITY", "COMMUNICATION",
    "SOCIAL_MEDIA", "FORUM_COMMUNITY", "VIDEO_STREAMING", "MUSIC_AUDIO",
    "ENTERTAINMENT", "GAMING", "DATING", "GAMBLING", "ADULT_SEXUAL", "DRUGS",
    "WEAPONS", "GRAPHIC_VIOLENCE", "HATE_EXTREMISM", "MALWARE_PHISHING",
    "SCAM_FRAUD", "PROXY_VPN_BYPASS", "FILE_SHARING", "CLOUD_STORAGE",
    "DOWNLOAD_SITE", "ADVERTISEMENT", "OTHER", "UNKNOWN"
];

const DEFAULT_CATEGORY_POLICY = {
    "GAMBLING": "BLOCK",
    "ADULT_SEXUAL": "BLOCK",
    "DRUGS": "BLOCK",
    "WEAPONS": "BLOCK",
    "GRAPHIC_VIOLENCE": "BLOCK",
    "HATE_EXTREMISM": "BLOCK",
    "MALWARE_PHISHING": "BLOCK",
    "SCAM_FRAUD": "BLOCK",
    "PROXY_VPN_BYPASS": "BLOCK"
};

async function getCategoryPolicy(env) {
    try {
        const raw = await env.SAFEBROWSER_KV.get('category_policy');
        if (raw) return JSON.parse(raw);
    } catch(e) {}
    return DEFAULT_CATEGORY_POLICY;
}

// Scans pending domains and groups by hostname to find best context
function getBestContexts(pendingArray) {
    const map = new Map();
    for (const item of pendingArray) {
        if (!item || !item.domain) continue;
        const dom = String(item.domain).toLowerCase().trim().replace(/^www\./, '');
        const title = String(item.title || '');
        const desc = String(item.description || '');
        const url = String(item.url || '');
        
        let score = 0;
        if (title.length > 3 && title.toLowerCase() !== 'home') score += 2;
        if (desc.length > 5) score += 3;
        if (url.length > dom.length + 8) score += 1;
        
        const current = map.get(dom);
        if (!current || score > current.score) {
            map.set(dom, { domain: dom, title, description: desc, url, score, attempts: item.attempts || 0 });
        }
    }
    return Array.from(map.values());
}

async function callAI(env, domainsBatch) {
    const apiKey = env.AI_API_KEY;
    if (!apiKey) throw new Error("AI_API_KEY not set");
    const model = env.AI_MODEL || "gpt-4o-mini";
    const endpoint = "https://aicore.dpdns.org/v1/chat/completions";

    const prompt = `You are a strict internet safety classifier. Classify the following domains based on their title and description.
You MUST choose exactly ONE category from this list:
${TAXONOMY.join(", ")}

Return ONLY a JSON array of objects with keys: "domain", "category", "confidence" (0.0 to 1.0), and "reason".
Do not include markdown blocks or any other text.

Domains to classify:
${JSON.stringify(domainsBatch.map(d => ({ domain: d.domain, title: d.title, description: d.description })), null, 2)}`;

    const response = await fetch(endpoint, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Authorization": `Bearer ${apiKey}`
        },
        body: JSON.stringify({
            model: model,
            messages: [{ role: "user", content: prompt }],
            temperature: 0.0
        })
    });

    if (!response.ok) {
        throw new Error(`AI API HTTP error: ${response.status}`);
    }

    const data = await response.json();
    let content = data.choices[0].message.content.trim();
    if (content.startsWith("\`\`\`json")) {
        content = content.substring(7);
        if (content.endsWith("\`\`\`")) content = content.slice(0, -3);
    }
    
    return JSON.parse(content);
}

// Background classification immediately after POST /api/domains
async function classifyBackground(env, newEntries) {
    try {
        if (!env.AI_API_KEY) return;
        
        const pendingRaw = await env.SAFEBROWSER_KV.get('pending_domains') || '[]';
        const pending = JSON.parse(pendingRaw);
        
        const newHostnames = [...new Set(newEntries.map(e => e.domain))];
        const relevantPending = pending.filter(p => newHostnames.includes(p.domain));
        
        const bestContexts = getBestContexts(relevantPending);
        if (bestContexts.length === 0) return;
        
        // Take up to 10 for immediate processing
        const batch = bestContexts.slice(0, 10);
        
        const aiResults = await callAI(env, batch);
        await processAiResults(env, aiResults, batch);
    } catch(e) {
        console.error("classifyBackground error:", e);
    }
}

// Scheduled retry mechanism
async function runScheduler(env, ctx) {
    if (!env.AI_API_KEY) return;

    try {
        const pendingRaw = await env.SAFEBROWSER_KV.get('pending_domains') || '[]';
        let pending = JSON.parse(pendingRaw);
        
        const classRaw = await env.SAFEBROWSER_KV.get('domain_classifications') || '{}';
        const classifications = JSON.parse(classRaw);
        
        pending = pending.filter(p => {
            if (classifications[p.domain]) return false; // already classified
            return true;
        });

        const bestContexts = getBestContexts(pending);
        
        const MAX_CHUNKS = 3;
        const BATCH_SIZE = 10;
        
        for (let i = 0; i < Math.min(bestContexts.length, MAX_CHUNKS * BATCH_SIZE); i += BATCH_SIZE) {
            const batch = bestContexts.slice(i, i + BATCH_SIZE);
            try {
                const aiResults = await callAI(env, batch);
                await processAiResults(env, aiResults, batch);
            } catch(e) {
                console.error("Scheduler API failure, stopping cron cycle:", e);
                // Increment attempts for this batch
                const pRaw = await env.SAFEBROWSER_KV.get('pending_domains') || '[]';
                let pList = JSON.parse(pRaw);
                batch.forEach(b => {
                    pList.forEach(p => {
                        if (p.domain === b.domain) p.attempts = (p.attempts || 0) + 1;
                    });
                });
                await env.SAFEBROWSER_KV.put('pending_domains', JSON.stringify(pList));
                break; // fail fast
            }
        }
    } catch(e) {
        console.error("Scheduler error:", e);
    }
}

async function processAiResults(env, aiResults, batch) {
    if (!Array.isArray(aiResults)) return;

    const classRaw = await env.SAFEBROWSER_KV.get('domain_classifications') || '{}';
    const classifications = JSON.parse(classRaw);
    
    let updatedClassifications = false;
    let successfulDomains = new Set();
    let lowConfidenceDomains = new Set();

    for (const res of aiResults) {
        if (!res.domain || !res.category) continue;
        const dom = res.domain.toLowerCase();
        
        // Concurrency check
        if (classifications[dom]) {
            successfulDomains.add(dom);
            continue;
        }

        if (res.confidence >= 0.90) {
            classifications[dom] = {
                category: TAXONOMY.includes(res.category) ? res.category : "OTHER",
                confidence: res.confidence,
                reason: res.reason,
                timestamp: Date.now()
            };
            updatedClassifications = true;
            successfulDomains.add(dom);
        } else {
            lowConfidenceDomains.add(dom);
        }
    }

    if (updatedClassifications) {
        await env.SAFEBROWSER_KV.put('domain_classifications', JSON.stringify(classifications));
        await rebuildEffectiveConfig(env);
    }

    // Update pending_domains safely
    const pRaw = await env.SAFEBROWSER_KV.get('pending_domains') || '[]';
    let pList = JSON.parse(pRaw);
    
    pList = pList.filter(p => {
        const dom = String(p.domain).toLowerCase();
        if (successfulDomains.has(dom)) return false; // remove successful
        if (lowConfidenceDomains.has(dom)) {
            p.lastResult = "LOW_CONFIDENCE";
            p.lastAttemptAt = Date.now();
        }
        return true;
    });
    
    await env.SAFEBROWSER_KV.put('pending_domains', JSON.stringify(pList));
}

async function rebuildEffectiveConfig(env) {
    const configRaw = await env.SAFEBROWSER_KV.get('config') || '{}';
    let config = JSON.parse(configRaw);
    
    const classRaw = await env.SAFEBROWSER_KV.get('domain_classifications') || '{}';
    const classifications = JSON.parse(classRaw);
    
    const policy = await getCategoryPolicy(env);
    
    const manualBlocks = config.manual_blocks || [];
    
    let effective = new Set(manualBlocks);
    
    for (const [dom, data] of Object.entries(classifications)) {
        if (policy[data.category] === "BLOCK") {
            effective.add(dom);
        }
    }
    
    config.blocked_domains = Array.from(effective);
    
    const parts = (config.version || '1.0.0').split('.');
    parts[2] = (parseInt(parts[2] || '0') + 1).toString();
    config.version = parts.join('.');
    config.updated = new Date().toISOString();
    
    await env.SAFEBROWSER_KV.put('config', JSON.stringify(config));
}
