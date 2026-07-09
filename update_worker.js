const fs = require('fs');
let code = fs.readFileSync('worker.js', 'utf8');

// 1. Add /api/unblock route
code = code.replace(
`        // ═══════════════════════════════════════════════════════════
        // 2) GET /api/config`,
`        // ═══════════════════════════════════════════════════════════
        // 1.5) POST /api/unblock
        // ═══════════════════════════════════════════════════════════
        if (url.pathname === '/api/unblock' && method === 'POST') {
            try {
                const body = await request.json();
                if (!body.domain) return jsonResponse({ ok: false }, 400, corsHeaders);
                const domain = String(body.domain).toLowerCase().trim().replace(/^www\\./, '');
                
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
        // 2) GET /api/config`
);

// 2. Add unblockRequests to /admin route
code = code.replace(
`                const ignoredRaw = await kvGet('ignored_domains', '[]');`,
`                const ignoredRaw = await kvGet('ignored_domains', '[]');
                const unblockReqsRaw = await kvGet('unblock_requests', '[]');
                const unblockReqs = JSON.parse(unblockReqsRaw);`
);

code = code.replace(
`                const html = adminDashboard(pending, ignored, config, adminPassword);`,
`                const html = adminDashboard(pending, ignored, config, adminPassword, unblockReqs);`
);

// 3. Add actions to POST /admin/action (add_shortcut, remove_shortcut, approve_unblock, deny_unblock)
code = code.replace(
`                // ── update_keywords ──`,
`                // ── add_shortcut ──
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

                // ── update_keywords ──`
);

// 4. Update adminDashboard definition
code = code.replace(
`function adminDashboard(pending, ignored, config, adminPassword) {`,
`function adminDashboard(pending, ignored, config, adminPassword, unblockReqs = []) {`
);

// 5. Update HTML to show Shortcuts and Unblock Requests
const newCards = `
      <!-- Unblock Requests -->
      <div class="card">
        <h2>🔓 Unblock Requests <span class="badge \${unblockReqs.length > 0 ? 'red' : 'gray'}">\${unblockReqs.length}</span></h2>
        <div class="domain-list">
          \${unblockReqs.length === 0 ? \`<div class="empty-state"><span class="icon">✅</span>No unblock requests</div>\` : unblockReqs.map(domain => \`
            <div class="blocked-row">
              <span class="blocked-name">\${escapeHtml(domain)}</span>
              <div>
                  <form method="POST" action="/admin/action" class="inline-form">
                    <input type="hidden" name="action" value="approve_unblock">
                    <input type="hidden" name="domain" value="\${escapeHtml(domain)}">
                    <button type="submit" class="btn-remove" style="color:#10b981;" title="Approve & Unblock">✓</button>
                  </form>
                  <form method="POST" action="/admin/action" class="inline-form">
                    <input type="hidden" name="action" value="deny_unblock">
                    <input type="hidden" name="domain" value="\${escapeHtml(domain)}">
                    <button type="submit" class="btn-remove" title="Deny & Dismiss">✕</button>
                  </form>
              </div>
            </div>
          \`).join('')}
        </div>
      </div>

      <!-- Quick Shortcuts -->
      <div class="card">
        <h2>🔗 Quick Shortcuts <span class="badge">\${(config.shortcuts || []).length}</span></h2>
        <form method="POST" action="/admin/action" class="input-group">
          <input type="hidden" name="action" value="add_shortcut">
          <input type="text" name="title" placeholder="Title (e.g. Google)" required style="width: 30%">
          <input type="url" name="url" placeholder="https://..." required style="width: 50%">
          <input type="text" name="icon" placeholder="Icon (e.g. 🔍)" style="width: 20%">
          <button type="submit" class="btn btn-primary">+ Add</button>
        </form>
        <div class="domain-list">
          \${(config.shortcuts || []).length === 0 ? \`<div class="empty-state"><span class="icon">🔗</span>No shortcuts configured</div>\` : (config.shortcuts || []).map(s => \`
            <div class="blocked-row">
              <span class="blocked-name">\${escapeHtml(s.icon)} \${escapeHtml(s.title)} <small style="opacity:0.5">(\${escapeHtml(s.url)})</small></span>
              <form method="POST" action="/admin/action" class="inline-form">
                <input type="hidden" name="action" value="remove_shortcut">
                <input type="hidden" name="url" value="\${escapeHtml(s.url)}">
                <button type="submit" class="btn-remove" title="Remove">✕</button>
              </form>
            </div>
          \`).join('')}
        </div>
      </div>
`;

code = code.replace(
`      <!-- Quick Info -->`,
newCards + `\n      <!-- Quick Info -->`
);

fs.writeFileSync('worker.js', code);
